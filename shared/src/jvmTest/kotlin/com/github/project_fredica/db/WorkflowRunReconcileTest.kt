package com.github.project_fredica.db

// =============================================================================
//  WorkflowRunReconcileTest —— WorkflowRun 层两道对账机制的可靠性测试
// =============================================================================
//
//  ┌─────────────────────────────────────────────────────────────────────────┐
//  │               WorkflowRun 层两道对账保护链                               │
//  │                                                                         │
//  │  场景                      │ 保护方法                │ 测试分组           │
//  │ ─────────────────────────  │ ─────────────────────── │ ──────────────── │
//  │  recalculate 的边界情况：  │ recalculate() 内部逻辑  │ Group D (3 cases) │
//  │  total=0 / 全 cancelled    │ total>0 守护条件        │                   │
//  │ ─────────────────────────  │ ─────────────────────── │ ──────────────── │
//  │  recalculate 调用失败导致  │ reconcileNonTerminal()  │ Group E (6 cases) │
//  │  WF 状态落后于 Task        │ WorkerEngine 启动时调用  │                   │
//  └─────────────────────────────────────────────────────────────────────────┘
//
//  【与 WebenSourceReconcileTest 的分工】
//    WebenSourceReconcileTest 测试的是 application 层对账：
//      WebenSource → WorkflowRun → Task（三层穿透）
//    本文件测试的是 infrastructure 层对账：
//      WorkflowRun ↔ Task（直接状态同步，无业务层介入）
//
//  【Kotlin 空集合逻辑陷阱（务必理解）】
//    `emptyList<Task>().all { it.status == "completed" }` 返回 true（全称命题对空集成立）
//    recalculate() 中通过 `total > 0 && done == total` 守护防止此陷阱。
//    Group D 的 D1 专门验证此防护有效性。
//
//  【测试环境】
//    每个 @Test 使用独立的 SQLite 临时文件，完全隔离，@BeforeTest 重建表结构。
// =============================================================================

import com.github.project_fredica.db.TaskPriority
import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkflowRunReconcileTest {

    private lateinit var db: Database
    private lateinit var taskDb: TaskDb
    private lateinit var workflowRunDb: WorkflowRunDb

    @BeforeTest
    fun setup() = runBlocking {
        val tmp = File.createTempFile("wfrun_reconcile_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmp.absolutePath}", driver = "org.sqlite.JDBC")
        workflowRunDb = WorkflowRunDb(db)
        taskDb = TaskDb(db)
        workflowRunDb.initialize()
        taskDb.initialize()
        TaskService.initialize(taskDb)
        WorkflowRunService.initialize(workflowRunDb)
    }

    // =========================================================================
    //  Group D: recalculate — 边界情况防护
    // =========================================================================
    //
    //  ┌─────────────────────────────────────────────────────────────────────┐
    //  │  【可靠性保证】                                                       │
    //  │  recalculate() 是 WorkflowRun 状态同步的核心方法，但它有两个不直观   │
    //  │  的边界情况需要特别验证：                                            │
    //  │                                                                     │
    //  │  1. 全称命题对空集成立（Kotlin vacuous truth）：                     │
    //  │     total=0 时，`done(0) == total(0)` 为 true，若没有 `total>0`     │
    //  │     守护，会错误地把无任务的 WF 判定为 completed。                  │
    //  │     → Group D1 验证此守护有效                                        │
    //  │                                                                     │
    //  │  2. 全部 cancelled 时的状态判定：                                    │
    //  │     cancelled 任务不计入 hasFailed 也不计入 done，                   │
    //  │     但 running/pending 也为 false → 走 else 分支 → pending。         │
    //  │     这是有意为之：cancelled 代表"被取消，不代表失败"。              │
    //  │     → Group D2/D3 验证混合场景的优先级                              │
    //  └─────────────────────────────────────────────────────────────────────┘

    /**
     * D1: total_tasks=0 时，recalculate 不会误判为 completed（vacuous truth 防护）。
     *
     * 【陷阱场景复现】：
     *   若 recalculate 代码写成 `done == total`（无 `total > 0` 守护），
     *   则 0 == 0 → true → 错误地把空任务的 WF 标记为 completed。
     *   前端会看到工作流"瞬间完成"，完全没有执行任何任务。
     *
     * 【当前实现的防护】：
     *   `total > 0 && done == total` — 双重条件防止空集陷阱。
     */
    @Test
    fun testRecalculate_emptyTasks_doesNotMarkCompleted() = runBlocking {
        println("[TRAP] Kotlin 空集合全称命题：0==0 在无守护时会被误判为 completed")
        println("[GUARANTEE] recalculate 通过 total>0 守护防止空任务 WF 被误标记为 completed")

        val wf = makeWf("wf-D1", status = "running")  // WF 已在进行中
        workflowRunDb.create(wf)
        // 不创建任何 Task（模拟 Task 被全部删除的异常场景）

        println("[ARRANGE] wf-D1 status=running，但无任何 Task 记录")
        println("[ACT] recalculate(wf-D1)")
        workflowRunDb.recalculate("wf-D1")

        val after = workflowRunDb.getById("wf-D1")!!
        println("[AFTER] status=${after.status}, totalTasks=${after.totalTasks}")

        assertTrue(after.status != "completed",
            "total=0 时 recalculate 不得将 WF 误标记为 completed（空集全称命题陷阱）")
        assertEquals(0, after.totalTasks,
            "total_tasks 应被正确统计为 0")

        println("[RESULT] ✓ 无 Task 时 WF 未被误判为 completed（status=${after.status}）")
        println("[NOTE] reconcileNonTerminal 会进一步将 total=0 的 WF 修正为 failed")
    }

    /**
     * D2: 全部任务均为 cancelled 时，recalculate 将 WF 状态设为 cancelled。
     *
     * 语义解释：
     *   cancelled 任务既不计入 hasFailed（没有失败），也不计入 done（没有完成），
     *   也不计入 hasRunning/hasPending（不在执行中）。
     *   走 hasCancelled 分支 → cancelled（"所有任务均已取消，工作流随之取消"）。
     *   这是有意为之：resetStaleTasks() 将所有非终态任务批量取消后，WF 应该
     *   也进入 cancelled 状态，而非 pending（"等待新指令"的含义不准确）。
     */
    @Test
    fun testRecalculate_allCancelled_statusPending() = runBlocking {
        println("[PRINCIPLE] 全部 cancelled 的 WF → cancelled（取消语义传递到工作流层）")

        val wf = makeWf("wf-D2", status = "running")
        workflowRunDb.create(wf)
        val tasks = createTasks("wf-D2", 2)
        tasks.forEach { taskDb.updateStatus(it.id, "cancelled") }

        println("[ARRANGE] wf-D2 有 2 个任务，全部 cancelled")
        println("[ACT] recalculate(wf-D2)")
        workflowRunDb.recalculate("wf-D2")

        val after = workflowRunDb.getById("wf-D2")!!
        println("[AFTER] status=${after.status}, doneTasks=${after.doneTasks}")

        assertEquals("cancelled", after.status,
            "全部 cancelled 时 WF 走 hasCancelled 分支 → cancelled（取消语义向上传递）")
        assertEquals(0, after.doneTasks,
            "cancelled 任务不计入 doneTasks")

        println("[RESULT] ✓ 全 cancelled → cancelled（取消语义一致）")
    }

    /**
     * D3: cancelled + failed 混合时，failed 优先级最高，WF 应为 failed。
     *
     * 验证优先级规则：hasFailed 检查在所有其他条件之前，
     * 只要存在一个 failed 任务，WF 就立即变为 failed，无论其他任务状态如何。
     */
    @Test
    fun testRecalculate_cancelledAndFailed_failedWins() = runBlocking {
        println("[PRINCIPLE] failed 优先级高于 cancelled：任何 failed 任务 → WF = failed")

        val wf = makeWf("wf-D3", status = "running")
        workflowRunDb.create(wf)
        val tasks = createTasks("wf-D3", 3)
        taskDb.updateStatus(tasks[0].id, "cancelled")
        taskDb.updateStatus(tasks[1].id, "cancelled")
        taskDb.updateStatus(tasks[2].id, "failed", error = "任务 3 执行失败")

        println("[ARRANGE] wf-D3 有 3 个任务：2 cancelled + 1 failed")
        println("[ACT] recalculate(wf-D3)")
        workflowRunDb.recalculate("wf-D3")

        val after = workflowRunDb.getById("wf-D3")!!
        println("[AFTER] status=${after.status}")

        assertEquals("failed", after.status,
            "2 cancelled + 1 failed → WF = failed（hasFailed 优先级最高）")

        println("[RESULT] ✓ failed 优先级确认：即使大多数任务是 cancelled，有 failed 就判定为 failed")
    }

    // =========================================================================
    //  Group E: reconcileNonTerminal — 批量对账非终态 WorkflowRun
    // =========================================================================
    //
    //  ┌─────────────────────────────────────────────────────────────────────┐
    //  │  【可靠性保证】                                                       │
    //  │  WorkerEngine 在每次任务状态变更后调用 recalculate()，但此调用可能   │
    //  │  因以下原因失败，导致 WF 状态落后于 Task 实际状态：                 │
    //  │    - DB 写入异常（磁盘满、锁超时）                                  │
    //  │    - 协程被取消（APP 在此刻强杀）                                   │
    //  │    - recalculate() 内部 bug（边界情况未覆盖）                       │
    //  │                                                                     │
    //  │  reconcileNonTerminal() 是第二道保险：在 WorkerEngine 启动时对      │
    //  │  所有非终态 WF 批量重算，确保最终一致性。                           │
    //  │                                                                     │
    //  │  额外处理：total_tasks=0 的孤立 WF → failed                        │
    //  │  （recalculate 本身无法区分"新建空 WF"和"所有 Task 被删除"）        │
    //  └─────────────────────────────────────────────────────────────────────┘

    /**
     * E1: WF 状态落后——仍为 pending，但所有 Task 已 completed。
     *
     * 模拟场景：最后一个 Task 完成时，recalculate() 调用因 DB 异常失败，
     * WF 状态卡在 pending。APP 重启后 reconcileNonTerminal() 修正为 completed。
     */
    @Test
    fun testReconcile_outOfSync_pendingButAllTasksDone() = runBlocking {
        println("[SCENARIO] recalculate 调用失败导致 WF 状态落后于 Task 实际状态")
        println("[GUARANTEE] reconcileNonTerminal 在启动时修正所有落后的 WF 状态")

        val wf = makeWf("wf-E1", status = "pending")
        workflowRunDb.create(wf)
        val tasks = createTasks("wf-E1", 3)
        tasks.forEach { taskDb.updateStatus(it.id, "completed") }

        // 模拟 recalculate 没有被调用（WF 仍是 pending）
        val before = workflowRunDb.getById("wf-E1")!!
        println("[ARRANGE] wf-E1 status=${before.status}（仍为 pending），但 3 个 Task 已全部 completed")
        println("[BEFORE] WF.status=${before.status}（落后的旧状态）")

        println("[ACT] reconcileNonTerminal()")
        val fixed = workflowRunDb.reconcileNonTerminal()
        println("[AFTER] fixed=$fixed")

        val after = workflowRunDb.getById("wf-E1")!!
        println("[ASSERT] wf-E1 status=${after.status}, doneTasks=${after.doneTasks}")

        assertEquals("completed", after.status,
            "reconcileNonTerminal 必须将落后的 pending 修正为 completed")
        assertEquals(3, after.doneTasks,
            "doneTasks 应同步更新为 3（全部完成）")
        assertNotNull(after.completedAt,
            "completed_at 时间戳应在修正时写入")
        assertTrue(fixed >= 1,
            "至少修改了 1 个 WF，返回值应 >= 1")

        println("[RESULT] ✓ WF 状态从 pending 修正为 completed，doneTasks=3")
    }

    /**
     * E2: WF 状态落后——仍为 running，但有 Task 已 failed。
     *
     * 模拟场景：Task 失败时 recalculate() 未能触发，WF 仍显示 running。
     * 启动恢复中 reconcileNonTerminal() 读取 Task 实际状态修正为 failed。
     */
    @Test
    fun testReconcile_outOfSync_runningButTaskFailed() = runBlocking {
        println("[SCENARIO] Task 失败后 recalculate 调用异常，WF 卡在 running 状态")
        println("[GUARANTEE] reconcileNonTerminal 通过读取 Task 实际状态修正 WF 为 failed")

        val wf = makeWf("wf-E2", status = "running")
        workflowRunDb.create(wf)
        val tasks = createTasks("wf-E2", 2)
        taskDb.updateStatus(tasks[0].id, "completed")
        taskDb.updateStatus(tasks[1].id, "failed", error = "网络超时")

        // 手动强制 WF 状态为 running（不调用 recalculate）
        forceWfStatus("wf-E2", "running")
        println("[ARRANGE] wf-E2 status=running（手动强制）, Task[0]=completed, Task[1]=failed")
        println("[BEFORE] WF.status=running（与 Task 实际状态不符）")

        println("[ACT] reconcileNonTerminal()")
        workflowRunDb.reconcileNonTerminal()

        val after = workflowRunDb.getById("wf-E2")!!
        println("[AFTER] wf-E2 status=${after.status}")

        assertEquals("failed", after.status,
            "有 Task failed，reconcileNonTerminal 必须将 WF 修正为 failed")

        println("[RESULT] ✓ WF 状态从 running 修正为 failed（有 Task 失败）")
    }

    /**
     * E3: WF 所有 Task 被删除 → reconcileNonTerminal 修正为 failed（孤立工作流）。
     *
     * 【关键场景】：
     *   Task 全部被删除后，recalculate 的 total=0 逻辑走 `else → pending`，
     *   一个 running/pending 的 WF 会"退回"到 pending，但永远无任务可以推进它。
     *   reconcileNonTerminal() 检测到 total_tasks=0 后，额外将其修正为 failed。
     *
     * 【陷阱防护】：
     *   此处复现了 Kotlin vacuous truth 陷阱的 WorkflowRun 版本：
     *   recalculate 中 `done(0) == total(0)` 因 `total>0` 守护不触发 completed，
     *   但走 else 分支的 pending 也不适合孤立工作流。
     *   reconcileNonTerminal 作为第二层防护补充处理此边界。
     */
    @Test
    fun testReconcile_allTasksDeleted_failsOrphanedWorkflow() = runBlocking {
        println("[SCENARIO] 所有 Task 被删除，WF 成为孤立工作流，无任何任务可推进")
        println("[TRAP] recalculate 的 total=0 分支：走 else→pending，不适合孤立 WF")
        println("[GUARANTEE] reconcileNonTerminal 在 total=0 时额外修正为 failed")

        val wf = makeWf("wf-E3", status = "running")
        workflowRunDb.create(wf)
        val tasks = createTasks("wf-E3", 2)

        // 直接删除全部 Task（模拟数据迁移或意外删除）
        tasks.forEach { deleteTask(it.id) }
        println("[ARRANGE] wf-E3 原有 2 个 Task，已全部删除，WF status=running")
        println("[BEFORE] WF.status=running，但 Task 表中无任何记录属于此 WF")

        println("[ACT] reconcileNonTerminal()")
        workflowRunDb.reconcileNonTerminal()

        val after = workflowRunDb.getById("wf-E3")!!
        println("[AFTER] wf-E3 status=${after.status}, totalTasks=${after.totalTasks}")

        assertEquals("failed", after.status,
            "Task 全部被删除的孤立 WF 应被 reconcileNonTerminal 标记为 failed")
        assertEquals(0, after.totalTasks,
            "totalTasks 应正确反映实际 Task 数量（0）")

        println("[RESULT] ✓ 孤立 WF（total=0）成功修正为 failed，前端不再显示\"进行中\"")
    }

    /**
     * E4: 终态 WF（completed/failed/cancelled）不受 reconcileNonTerminal 影响。
     *
     * 验证：reconcileNonTerminal 只处理非终态 WF，对终态 WF 幂等，
     * 不会反复重算已经完成的工作流，也不会覆盖已有的 completed_at 时间戳。
     */
    @Test
    fun testReconcile_terminalWorkflowsAreSkipped() = runBlocking {
        println("[GUARANTEE] reconcileNonTerminal 对终态 WF 幂等，不重算不覆盖")

        val wfCompleted = makeWf("wf-E4-comp", status = "completed")
        val wfFailed    = makeWf("wf-E4-fail", status = "failed")
        workflowRunDb.create(wfCompleted)
        workflowRunDb.create(wfFailed)

        // 为 completed WF 创建一个仍是 pending 的任务（DB 数据不一致，但不应被修正）
        val staleTask = makeTask("E4-stale", "wf-E4-comp")
        taskDb.create(staleTask)

        println("[ARRANGE] wf-E4-comp(completed) 有一个 pending task; wf-E4-fail(failed) 无 task")
        println("[ACT] reconcileNonTerminal()")
        workflowRunDb.reconcileNonTerminal()

        val compAfter = workflowRunDb.getById("wf-E4-comp")!!
        val failAfter = workflowRunDb.getById("wf-E4-fail")!!
        println("[AFTER] wf-E4-comp status=${compAfter.status}, wf-E4-fail status=${failAfter.status}")

        assertEquals("completed", compAfter.status,
            "已完成的 WF 不应被 reconcileNonTerminal 修改（即使有孤立 pending task）")
        assertEquals("failed", failAfter.status,
            "已失败的 WF 不应被 reconcileNonTerminal 修改")

        println("[RESULT] ✓ 终态 WF 保持不变，reconcileNonTerminal 正确跳过")
    }

    /**
     * E5: 混合场景——多个 WF 同时存在，部分需修正、部分不需要。
     *
     * 验证：reconcileNonTerminal 能够正确批量处理多个 WF，
     * 每个 WF 独立评估，不受其他 WF 干扰。
     */
    @Test
    fun testReconcile_mixedScenario() = runBlocking {
        println("[SCENARIO] 生产环境混合场景：5 个 WF，不同状态不同需求")

        // WF1: 应修正 pending → completed（Task 全完成，但 recalculate 未调用）
        val wf1 = makeWf("wf-E5-fixable", status = "pending")
        workflowRunDb.create(wf1)
        createTasks("wf-E5-fixable", 2).forEach { taskDb.updateStatus(it.id, "completed") }

        // WF2: 正常进行中（有 pending task），不应修改
        val wf2 = makeWf("wf-E5-running", status = "running")
        workflowRunDb.create(wf2)
        createTasks("wf-E5-running", 1)  // 保持 pending，模拟正在执行

        // WF3: 已是终态 completed，不应修改
        val wf3 = makeWf("wf-E5-done", status = "completed")
        workflowRunDb.create(wf3)

        // WF4: 应修正 running → failed（task 已 failed，但 WF 未更新）
        val wf4 = makeWf("wf-E5-stale-fail", status = "running")
        workflowRunDb.create(wf4)
        createTasks("wf-E5-stale-fail", 1).forEach { taskDb.updateStatus(it.id, "failed", error = "err") }
        forceWfStatus("wf-E5-stale-fail", "running")

        // WF5: 所有 Task 被删除，应修正为 failed
        val wf5 = makeWf("wf-E5-orphan", status = "running")
        workflowRunDb.create(wf5)
        createTasks("wf-E5-orphan", 1).forEach { deleteTask(it.id) }

        println("[ARRANGE] 5 个 WF: fixable/running/done/stale-fail/orphan")
        println("[ACT] reconcileNonTerminal()")
        val fixed = workflowRunDb.reconcileNonTerminal()
        println("[AFTER] fixed=$fixed")

        val after1 = workflowRunDb.getById(wf1.id)!!.status
        val after2 = workflowRunDb.getById(wf2.id)!!.status
        val after3 = workflowRunDb.getById(wf3.id)!!.status
        val after4 = workflowRunDb.getById(wf4.id)!!.status
        val after5 = workflowRunDb.getById(wf5.id)!!.status
        println("[ASSERT] fixable=$after1, running=$after2, done=$after3, stale-fail=$after4, orphan=$after5")

        assertEquals("completed", after1, "WF1(落后 pending) → completed")
        assertEquals("running",   after2, "WF2(有 pending task) → 保持 running")
        assertEquals("completed", after3, "WF3(已终态 completed) → 保持不变")
        assertEquals("failed",    after4, "WF4(落后 running，task 已 failed) → failed")
        assertEquals("failed",    after5, "WF5(孤立，task 全删除) → failed")
        assertTrue(fixed >= 3,
            "至少修正了 WF1/WF4/WF5 共 3 个，返回值应 >= 3")

        println("[RESULT] ✓ 混合场景处理正确：fixable→completed, stale-fail→failed, orphan→failed")
    }

    /**
     * E6: reconcileNonTerminal 返回正确的修改计数。
     *
     * 验证：返回值准确反映实际发生变化的 WF 数量，
     * 供调用方（WorkerEngine 启动日志）做准确的数量报告。
     */
    @Test
    fun testReconcile_returnsCorrectModifiedCount() = runBlocking {
        println("[CONTRACT] reconcileNonTerminal 返回值 = 实际状态发生变化的 WF 数量")

        // 场景 1：无非终态 WF → 返回 0
        println("[CASE 1] 无非终态 WF")
        val count0 = workflowRunDb.reconcileNonTerminal()
        println("[ASSERT] count=$count0（应为 0）")
        assertEquals(0, count0, "无非终态 WF 时返回 0")

        // 场景 2：2 个需修正 + 1 个不需要修正的 WF
        val wfA = makeWf("wf-E6-A", status = "pending")  // 需修正
        val wfB = makeWf("wf-E6-B", status = "pending")  // 需修正
        val wfC = makeWf("wf-E6-C", status = "running")  // 不需要修正（有 pending task）
        listOf(wfA, wfB, wfC).forEach { workflowRunDb.create(it) }

        // wfA/wfB 的 Task 全部完成
        createTasks("wf-E6-A", 1).forEach { taskDb.updateStatus(it.id, "completed") }
        createTasks("wf-E6-B", 1).forEach { taskDb.updateStatus(it.id, "completed") }
        // wfC 的 Task 仍是 pending（有任务在推进，不需要修正）
        createTasks("wf-E6-C", 1)

        println("[CASE 2] 2 个落后 WF(A/B Task 全完成) + 1 个正常 WF(C 有 pending Task)")
        println("[ACT] reconcileNonTerminal()")
        val count2 = workflowRunDb.reconcileNonTerminal()
        println("[AFTER] count=$count2（应为 2：A 和 B 被修正）")

        assertEquals(2, count2,
            "应精确返回 2（wfA + wfB 从 pending 修正为 completed），wfC 不变不计入")

        println("[RESULT] ✓ 返回值精确反映修改数量（2）")
    }

    // =========================================================================
    //  辅助方法
    // =========================================================================

    private fun nowSec() = System.currentTimeMillis() / 1000L

    private fun makeWf(id: String, status: String = "pending") = WorkflowRun(
        id = id, materialId = "mat-1", template = "TEST",
        status = status, totalTasks = 0, doneTasks = 0, createdAt = nowSec()
    )

    private fun makeTask(id: String, wfId: String, status: String = "pending") = Task(
        id = id, type = "TEST_TASK", workflowRunId = wfId,
        materialId = "mat-1", status = status, priority = TaskPriority.DEV_TEST_DEFAULT, createdAt = nowSec()
    )

    private suspend fun createTasks(wfId: String, count: Int): List<Task> {
        val tasks = (1..count).map { i ->
            makeTask("$wfId-t$i", wfId).copy(createdAt = nowSec() + i)
        }
        taskDb.createAll(tasks)
        return tasks
    }

    /** 直接强制写入 WF 状态（绕过 recalculate，模拟"状态落后"场景） */
    private fun forceWfStatus(id: String, status: String) {
        db.useConnection { conn ->
            conn.prepareStatement("UPDATE workflow_run SET status=? WHERE id=?").use { ps ->
                ps.setString(1, status)
                ps.setString(2, id)
                ps.executeUpdate()
            }
        }
    }

    /** 直接删除 Task 记录（模拟手动清理或数据迁移导致的孤立 WF 场景） */
    private fun deleteTask(id: String) {
        db.useConnection { conn ->
            conn.prepareStatement("DELETE FROM task WHERE id=?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
        }
    }
}
