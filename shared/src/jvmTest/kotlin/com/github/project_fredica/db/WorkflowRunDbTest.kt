package com.github.project_fredica.db

// =============================================================================
// WorkflowRunDbTest —— 工作流运行实例状态联动的单元测试
// =============================================================================
//
// 测试范围：WorkflowRunDb 的核心运行时行为
//   1. create / getById       — 创建与查询，验证字段正确落库
//   2. recalculate（全完成）  — 所有子任务 completed → workflow_run = completed
//   3. recalculate（部分完成）— 部分子任务 completed → workflow_run = running
//   4. recalculate（有失败）  — 任意子任务 failed → workflow_run = failed（优先于其他状态）
//
// 测试环境：每个测试用例独立的 SQLite 临时文件（@BeforeTest 重新创建）。
// =============================================================================

import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkflowRunDbTest {

    private lateinit var db: Database
    private lateinit var taskDb: TaskDb
    private lateinit var workflowRunDb: WorkflowRunDb

    /**
     * 每个测试前重建一个干净的临时文件数据库。
     * workflow_run 和 task 表同时初始化，因为 recalculate() 需要读 task 表。
     */
    @BeforeTest
    fun setup() = runBlocking {
        val tmpFile = File.createTempFile("workflowrundbtest_", ".db").also { it.deleteOnExit() }
        db = Database.connect(
            url    = "jdbc:sqlite:${tmpFile.absolutePath}",
            driver = "org.sqlite.JDBC",
        )
        workflowRunDb = WorkflowRunDb(db)
        taskDb        = TaskDb(db)
        workflowRunDb.initialize()
        taskDb.initialize()
        TaskService.initialize(taskDb)
        WorkflowRunService.initialize(workflowRunDb)
    }

    // ── 测试 1：创建与查询 ────────────────────────────────────────────────────

    /**
     * 证明目的：create() 正确持久化所有字段，getById() 能准确读回。
     *
     * 证明过程：
     *   1. 创建一条 totalTasks=3 的工作流运行实例。
     *   2. 用 getById() 读回，断言以下字段值与写入一致：
     *      - status = "pending"（默认初始状态）
     *      - totalTasks = 3（写入值）
     *      - doneTasks = 0（尚未执行任何任务）
     */
    @Test
    fun testCreate() = runBlocking {
        workflowRunDb.create(workflowRun("wr-1", totalTasks = 3))

        val found = workflowRunDb.getById("wr-1")
        assertNotNull(found, "刚创建的运行实例应能通过 getById 查到")
        assertEquals("pending", found.status, "新建运行实例的初始状态应为 pending")
        assertEquals(3, found.totalTasks, "totalTasks 应与写入值一致")
        assertEquals(0, found.doneTasks, "新建运行实例的 doneTasks 应为 0")
    }

    // ── 测试 2：recalculate — 全部完成 ───────────────────────────────────────

    /**
     * 证明目的：所有子任务都 completed 后，recalculate() 将状态更新为 completed，
     *           并自动记录 completed_at 时间戳。
     *
     * 证明过程：
     *   1. 创建运行实例 + 3 个子任务。
     *   2. 将全部 3 个任务的状态改为 "completed"。
     *   3. 调用 recalculate()。
     *   4. 重新查询，断言：
     *      - status = "completed"
     *      - doneTasks = 3（完成数量统计正确）
     *      - completedAt != null（时间戳被自动写入）
     */
    @Test
    fun testRecalculate_allCompleted() = runBlocking {
        workflowRunDb.create(workflowRun("wr-all-done"))
        createTasks("wr-all-done", 3)

        taskDb.listByWorkflowRun("wr-all-done").forEach { taskDb.updateStatus(it.id, "completed") }
        workflowRunDb.recalculate("wr-all-done")

        val updated = workflowRunDb.getById("wr-all-done")!!
        assertEquals("completed", updated.status, "所有任务完成后运行实例应变为 completed")
        assertEquals(3, updated.doneTasks, "doneTasks 应等于全部子任务数量")
        assertTrue(updated.completedAt != null, "进入 completed 状态时应自动记录 completed_at")
    }

    // ── 测试 3：recalculate — 部分完成 ───────────────────────────────────────

    /**
     * 证明目的：部分子任务完成时，recalculate() 将状态更新为 running，
     *           并正确统计 doneTasks 数量。
     *
     * 证明过程：
     *   1. 创建运行实例 + 3 个子任务（均为 pending）。
     *   2. 将第 1 个任务改为 "completed"，其余保持 pending。
     *   3. 调用 recalculate()。
     *   4. 断言 status = "running"、doneTasks = 1。
     */
    @Test
    fun testRecalculate_partialDone() = runBlocking {
        workflowRunDb.create(workflowRun("wr-partial"))
        val tasks = createTasks("wr-partial", 3)

        taskDb.updateStatus(tasks[0].id, "completed")
        workflowRunDb.recalculate("wr-partial")

        val updated = workflowRunDb.getById("wr-partial")!!
        assertEquals("running", updated.status, "有任务未完成时运行实例应为 running")
        assertEquals(1, updated.doneTasks, "只完成了 1 个任务，doneTasks 应为 1")
    }

    // ── 测试 4：recalculate — 有任务失败 ─────────────────────────────────────

    /**
     * 证明目的：任意子任务进入 failed 状态后，recalculate() 立即将整体标记为 failed，
     *           即使同时有其他任务已经 completed。
     *
     * 证明过程：
     *   1. 创建运行实例 + 2 个子任务。
     *   2. 将第 1 个任务改为 "completed"，第 2 个任务改为 "failed"。
     *   3. 调用 recalculate()。
     *   4. 断言 status = "failed"。
     *
     * 验证的优先级规则：failed 状态的优先级高于 completed，
     * 只要有一个任务失败，整个运行实例就失败。
     */
    @Test
    fun testRecalculate_oneFailed() = runBlocking {
        workflowRunDb.create(workflowRun("wr-failed"))
        val tasks = createTasks("wr-failed", 2)

        taskDb.updateStatus(tasks[0].id, "completed")
        taskDb.updateStatus(tasks[1].id, "failed", error = "模拟执行失败")
        workflowRunDb.recalculate("wr-failed")

        assertEquals(
            "failed", workflowRunDb.getById("wr-failed")!!.status,
            "有子任务失败时，运行实例应立即变为 failed"
        )
    }

    // ── 测试 5：recalculate — 所有任务取消 ────────────────────────────────────

    /**
     * 证明目的：所有子任务均变为 cancelled 后，recalculate() 应将 WorkflowRun
     *           状态更新为 cancelled，而非原来错误的 "pending"。
     *
     * 证明过程：
     *   1. 创建运行实例 + 3 个子任务（均为 pending）。
     *   2. 将全部 3 个任务改为 "cancelled"（模拟 resetStaleTasks 在重启时的操作）。
     *   3. 调用 recalculate()。
     *   4. 断言 status = "cancelled"（修复前此处会错误返回 "pending"）。
     *
     * 修复背景：
     *   原 recalculate() 未跟踪 hasCancelled 标志，所有任务取消后各计数均为 0/false，
     *   走 else 分支返回 "pending"，导致 WorkflowRun 永远处于非终态。
     */
    @Test
    fun testRecalculate_allCancelled() = runBlocking {
        workflowRunDb.create(workflowRun("wr-all-cancelled"))
        val tasks = createTasks("wr-all-cancelled", 3)

        // 模拟应用重启时 resetStaleTasks() 将所有 pending 任务取消
        tasks.forEach { taskDb.updateStatus(it.id, "cancelled") }
        workflowRunDb.recalculate("wr-all-cancelled")

        assertEquals(
            "cancelled", workflowRunDb.getById("wr-all-cancelled")!!.status,
            "所有子任务取消后，WorkflowRun 应进入 cancelled 终态，而非 pending"
        )
    }

    // ── 测试 6：recalculate — 部分完成后其余取消 ──────────────────────────────

    /**
     * 证明目的：部分任务 completed、其余任务 cancelled 时，
     *           recalculate() 应将 WorkflowRun 状态更新为 cancelled。
     *
     * 证明过程：
     *   1. 创建运行实例 + 3 个子任务。
     *   2. 第 1 个任务 completed，第 2、3 个任务 cancelled。
     *   3. 调用 recalculate()，断言 status = "cancelled"。
     *
     * 语义说明：
     *   部分完成 + 部分取消不代表整体成功，WorkflowRun 应以 cancelled 结束，
     *   用户需在任务中心重新创建任务流以完成剩余步骤。
     */
    @Test
    fun testRecalculate_partialCancelledWithCompleted() = runBlocking {
        workflowRunDb.create(workflowRun("wr-partial-cancel"))
        val tasks = createTasks("wr-partial-cancel", 3)

        taskDb.updateStatus(tasks[0].id, "completed")
        taskDb.updateStatus(tasks[1].id, "cancelled")
        taskDb.updateStatus(tasks[2].id, "cancelled")
        workflowRunDb.recalculate("wr-partial-cancel")

        assertEquals(
            "cancelled", workflowRunDb.getById("wr-partial-cancel")!!.status,
            "部分完成 + 部分取消时，WorkflowRun 应为 cancelled（非 completed）"
        )
    }

    // ── 测试 7：recalculate — 依赖失败时下游任务被级联取消，WorkflowRun 变 failed ──

    /**
     * 证明目的：任务 A 失败后，recalculate() 会先级联取消依赖 A 的下游任务 B，
     *           再将 WorkflowRun 正确推进到 failed 终态（而非永久停留在 running）。
     *
     * 这是修复前的核心 bug 场景：
     *   - A 失败 → B 永久阻塞在 pending → recalculate 看到 hasPending=true → WorkflowRun=running
     *   - 修复后：recalculate 先调用 cancelBlockedTasks，B 被取消 → 再统计 → WorkflowRun=failed
     *
     * 证明过程：
     *   1. 创建 WorkflowRun + 任务 A（无依赖）+ 任务 B（依赖 A）。
     *   2. 将 A 标记为 failed。
     *   3. 调用 recalculate()。
     *   4. 断言：
     *      a. B 的状态已变为 cancelled（被级联取消）
     *      b. WorkflowRun 状态为 failed（而非 running）
     */
    @Test
    fun testRecalculate_dependencyFailed_cascadeCancelsDownstream() = runBlocking {
        workflowRunDb.create(workflowRun("wr-dep-fail"))
        val taskA = Task(id = "dep-fail-a", type = "STEP_A", workflowRunId = "wr-dep-fail", materialId = "material-1", createdAt = nowSec())
        val taskB = Task(id = "dep-fail-b", type = "STEP_B", workflowRunId = "wr-dep-fail", materialId = "material-1", dependsOn = """["dep-fail-a"]""", createdAt = nowSec() + 1)
        taskDb.createAll(listOf(taskA, taskB))

        // A 失败
        taskDb.updateStatus("dep-fail-a", "failed", error = "模拟失败")

        // recalculate 应先级联取消 B，再统计
        workflowRunDb.recalculate("wr-dep-fail")

        val tasks = taskDb.listByWorkflowRun("wr-dep-fail")
        assertEquals("cancelled", tasks.find { it.id == "dep-fail-b" }!!.status,
            "B 依赖 A(failed)，recalculate 后应被级联取消")

        assertEquals("failed", workflowRunDb.getById("wr-dep-fail")!!.status,
            "A 失败后 WorkflowRun 应为 failed，而非因 B=pending 误判为 running")
    }

    // ── 测试 8：recalculate — 三级依赖链，A 失败后多轮 recalculate 完成传播 ────

    /**
     * 证明目的：A → B → C 三级链中，A 失败后经过两次 recalculate()，
     *           B 和 C 均被级联取消，WorkflowRun 最终为 failed。
     *
     * 说明：cancelBlockedTasks 每次只处理"直接依赖已终止"的任务，
     *       因此 C（依赖 B）需要在 B 被取消后的第二次 recalculate 才会被处理。
     *       WorkerEngine 每次任务状态变更都会调用 recalculate()，
     *       所以实际运行中链式传播会自然完成。
     *
     * 证明过程：
     *   1. 创建 A → B → C 三级链。
     *   2. A 标记为 failed。
     *   3. 第一次 recalculate()：B 被取消，WorkflowRun=failed（因为 A=failed）。
     *   4. 第二次 recalculate()：C 被取消（依赖 B=cancelled）。
     *   5. 最终所有任务均为终态，WorkflowRun=failed。
     */
    @Test
    fun testRecalculate_threeLevel_chainPropagation() = runBlocking {
        workflowRunDb.create(workflowRun("wr-chain3"))
        val taskA = Task(id = "c3-a", type = "STEP_A", workflowRunId = "wr-chain3", materialId = "material-1", createdAt = nowSec())
        val taskB = Task(id = "c3-b", type = "STEP_B", workflowRunId = "wr-chain3", materialId = "material-1", dependsOn = """["c3-a"]""", createdAt = nowSec() + 1)
        val taskC = Task(id = "c3-c", type = "STEP_C", workflowRunId = "wr-chain3", materialId = "material-1", dependsOn = """["c3-b"]""", createdAt = nowSec() + 2)
        taskDb.createAll(listOf(taskA, taskB, taskC))

        taskDb.updateStatus("c3-a", "failed", error = "模拟失败")

        // 第一次 recalculate：B 被取消
        workflowRunDb.recalculate("wr-chain3")
        assertEquals("cancelled", taskDb.listByWorkflowRun("wr-chain3").find { it.id == "c3-b" }!!.status,
            "第一次 recalculate 后 B 应被取消")
        assertEquals("pending", taskDb.listByWorkflowRun("wr-chain3").find { it.id == "c3-c" }!!.status,
            "第一次 recalculate 后 C 尚未被取消")

        // 第二次 recalculate：C 被取消（依赖 B=cancelled）
        workflowRunDb.recalculate("wr-chain3")
        val finalTasks = taskDb.listByWorkflowRun("wr-chain3")
        assertEquals("cancelled", finalTasks.find { it.id == "c3-c" }!!.status,
            "第二次 recalculate 后 C 应被取消")

        assertEquals("failed", workflowRunDb.getById("wr-chain3")!!.status,
            "所有任务均为终态，WorkflowRun 应为 failed")
    }

    // ── 测试 9：recalculate — 菱形依赖，根节点失败后 WorkflowRun 正确终态 ──────

    /**
     * 证明目的：菱形 DAG 中根节点失败，经过两轮 recalculate() 后所有节点均进入终态，
     *           WorkflowRun 最终为 failed。
     *
     * 场景：A（failed）→ B、C → D
     *   第一轮 recalculate：B、C 被级联取消
     *   第二轮 recalculate：D 被级联取消（依赖 B=cancelled 且 C=cancelled）
     *   最终：WorkflowRun = failed
     */
    @Test
    fun testRecalculate_diamondDependency_allCascadeCancelled() = runBlocking {
        workflowRunDb.create(workflowRun("wr-diamond"))
        val taskA = Task(id = "dm-a", type = "STEP_A", workflowRunId = "wr-diamond", materialId = "material-1", createdAt = nowSec())
        val taskB = Task(id = "dm-b", type = "STEP_B", workflowRunId = "wr-diamond", materialId = "material-1", dependsOn = """["dm-a"]""",        createdAt = nowSec() + 1)
        val taskC = Task(id = "dm-c", type = "STEP_C", workflowRunId = "wr-diamond", materialId = "material-1", dependsOn = """["dm-a"]""",        createdAt = nowSec() + 2)
        val taskD = Task(id = "dm-d", type = "STEP_D", workflowRunId = "wr-diamond", materialId = "material-1", dependsOn = """["dm-b","dm-c"]""", createdAt = nowSec() + 3)
        taskDb.createAll(listOf(taskA, taskB, taskC, taskD))

        taskDb.updateStatus("dm-a", "failed", error = "根节点失败")

        // 第一轮：B、C 被取消
        workflowRunDb.recalculate("wr-diamond")
        val afterRound1 = taskDb.listByWorkflowRun("wr-diamond").associateBy { it.id }
        assertEquals("cancelled", afterRound1["dm-b"]!!.status, "第一轮后 B 应被取消")
        assertEquals("cancelled", afterRound1["dm-c"]!!.status, "第一轮后 C 应被取消")
        assertEquals("pending",   afterRound1["dm-d"]!!.status, "第一轮后 D 尚未被取消")
        assertEquals("failed", workflowRunDb.getById("wr-diamond")!!.status, "有 failed 任务，WorkflowRun 应为 failed")

        // 第二轮：D 被取消
        workflowRunDb.recalculate("wr-diamond")
        val afterRound2 = taskDb.listByWorkflowRun("wr-diamond").associateBy { it.id }
        assertEquals("cancelled", afterRound2["dm-d"]!!.status, "第二轮后 D 应被取消")
        assertEquals("failed", workflowRunDb.getById("wr-diamond")!!.status, "最终 WorkflowRun 应为 failed")
    }

    // ── 测试 10：recalculate — 混合链，失败链不影响成功链完成 ─────────────────

    /**
     * 证明目的：同一 WorkflowRun 中，失败链被级联取消后，
     *           独立的成功链仍能正常完成，WorkflowRun 最终为 failed（因有失败任务）。
     *
     * 场景：
     *   失败链：F1（failed）→ F2（pending → cancelled）
     *   成功链：S1（completed）→ S2（pending → completed）
     *
     * 验证：recalculate 不会误取消成功链的任务。
     */
    @Test
    fun testRecalculate_mixedChains_successChainCompletesNormally() = runBlocking {
        workflowRunDb.create(workflowRun("wr-mixed"))
        val taskF1 = Task(id = "mx-f1", type = "FAIL_A", workflowRunId = "wr-mixed", materialId = "material-1", createdAt = nowSec())
        val taskF2 = Task(id = "mx-f2", type = "FAIL_B", workflowRunId = "wr-mixed", materialId = "material-1", dependsOn = """["mx-f1"]""", createdAt = nowSec() + 1)
        val taskS1 = Task(id = "mx-s1", type = "OK_A",   workflowRunId = "wr-mixed", materialId = "material-1", createdAt = nowSec() + 2)
        val taskS2 = Task(id = "mx-s2", type = "OK_B",   workflowRunId = "wr-mixed", materialId = "material-1", dependsOn = """["mx-s1"]""", createdAt = nowSec() + 3)
        taskDb.createAll(listOf(taskF1, taskF2, taskS1, taskS2))

        taskDb.updateStatus("mx-f1", "failed")
        taskDb.updateStatus("mx-s1", "completed")

        // recalculate：F2 被级联取消，S2 不受影响
        workflowRunDb.recalculate("wr-mixed")
        val tasks = taskDb.listByWorkflowRun("wr-mixed").associateBy { it.id }
        assertEquals("cancelled", tasks["mx-f2"]!!.status, "F2 应被级联取消")
        assertEquals("pending",   tasks["mx-s2"]!!.status, "S2 依赖已完成的 S1，不应被取消")

        // S2 完成后再次 recalculate
        taskDb.updateStatus("mx-s2", "completed")
        workflowRunDb.recalculate("wr-mixed")

        // 有 F1=failed，WorkflowRun 应为 failed（即使 S1/S2 都完成了）
        assertEquals("failed", workflowRunDb.getById("wr-mixed")!!.status,
            "有失败任务时 WorkflowRun 应为 failed，即使其他链已完成")
    }

    // ── 测试 11：recalculate — 部分依赖失败（多前置任务中有一个失败）────────────

    /**
     * 证明目的：任务 B 依赖 A 和 X，A 失败但 X 完成，
     *           recalculate() 后 B 被级联取消，WorkflowRun 为 failed。
     */
    @Test
    fun testRecalculate_partialDependencyFailed() = runBlocking {
        workflowRunDb.create(workflowRun("wr-partial-dep"))
        val taskA = Task(id = "pd-a", type = "STEP_A", workflowRunId = "wr-partial-dep", materialId = "material-1", createdAt = nowSec())
        val taskX = Task(id = "pd-x", type = "STEP_X", workflowRunId = "wr-partial-dep", materialId = "material-1", createdAt = nowSec() + 1)
        val taskB = Task(id = "pd-b", type = "STEP_B", workflowRunId = "wr-partial-dep", materialId = "material-1", dependsOn = """["pd-a","pd-x"]""", createdAt = nowSec() + 2)
        taskDb.createAll(listOf(taskA, taskX, taskB))

        taskDb.updateStatus("pd-a", "failed")
        taskDb.updateStatus("pd-x", "completed")

        workflowRunDb.recalculate("wr-partial-dep")

        val tasks = taskDb.listByWorkflowRun("wr-partial-dep").associateBy { it.id }
        assertEquals("cancelled", tasks["pd-b"]!!.status,
            "B 的前置任务 A 已失败，即使 X 完成，B 也应被级联取消")
        assertEquals("failed", workflowRunDb.getById("wr-partial-dep")!!.status)
    }

    // ── 测试 12：recalculate — 宽 DAG，多个独立任务部分失败 ──────────────────

    /**
     * 证明目的：宽 DAG 中（多个并行任务），部分任务失败不影响其他独立任务的正常完成，
     *           WorkflowRun 最终为 failed。
     *
     * 场景：A、B、C 三个并行任务（无依赖关系）
     *   A → completed
     *   B → failed
     *   C → completed
     *
     * 验证：recalculate 正确统计，WorkflowRun = failed（因 B 失败）。
     */
    @Test
    fun testRecalculate_wideDag_partialFailure() = runBlocking {
        workflowRunDb.create(workflowRun("wr-wide"))
        val tasks = createTasks("wr-wide", 3)

        taskDb.updateStatus(tasks[0].id, "completed")
        taskDb.updateStatus(tasks[1].id, "failed", error = "B 失败")
        taskDb.updateStatus(tasks[2].id, "completed")

        workflowRunDb.recalculate("wr-wide")

        val wf = workflowRunDb.getById("wr-wide")!!
        assertEquals("failed", wf.status, "有任务失败时 WorkflowRun 应为 failed")
        assertEquals(2, wf.doneTasks, "doneTasks 只统计 completed 任务，应为 2")
        assertEquals(3, wf.totalTasks, "totalTasks 应为 3")
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────────────

    private fun nowSec() = System.currentTimeMillis() / 1000L

    /** 构造一个标准测试工作流运行实例。 */
    private fun workflowRun(id: String, totalTasks: Int = 3) = WorkflowRun(
        id = id, materialId = "material-1",
        template = "TEST_TEMPLATE", status = "pending",
        totalTasks = totalTasks, doneTasks = 0,
        createdAt = nowSec(),
    )

    /**
     * 为指定工作流运行实例批量创建 [count] 个任务，createdAt 依次递增 1 秒确保排序稳定。
     * 返回已创建的任务列表，便于测试中直接操作具体任务 ID。
     */
    private suspend fun createTasks(workflowRunId: String, count: Int): List<Task> {
        val tasks = (1..count).map { i ->
            Task(
                id = "$workflowRunId-t$i", type = "DOWNLOAD_VIDEO",
                workflowRunId = workflowRunId, materialId = "material-1",
                createdAt = nowSec() + i,
            )
        }
        taskDb.createAll(tasks)
        return tasks
    }
}
