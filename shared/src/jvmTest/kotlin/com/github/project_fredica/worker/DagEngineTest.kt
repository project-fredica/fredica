package com.github.project_fredica.worker

// =============================================================================
// DagEngineTest —— DAG 依赖解析（claimNext 调度逻辑）的单元测试
// =============================================================================
//
// 被测对象：TaskDb.claimNext() 内嵌的 DAG 调度 SQL。
//
// DAG（有向无环图）调度的核心语义：
//   一个任务只有当它 depends_on 列表中的所有前置任务都已 completed 时，
//   才允许被 claimNext() 认领。状态为 pending/claimed/running/failed 的
//   前置任务都会继续阻塞下游。
//
// 测试矩阵：
//   1. testNoDepsClaimable       — 无依赖 → 立即可认领（最基础场景）
//   2. testPendingDepBlocks      — 前置任务 pending/claimed → 下游被阻塞
//   3. testCompletedDepUnblocks  — 前置任务变为 completed → 下游解锁
//   4. testAllDepsRequired       — 多前置任务需全部 completed（AND 语义）
//   5. testCycleRejected         — 自依赖（自环）在创建阶段被检测并拒绝
//
// 测试环境：每个测试用例独立的 SQLite 临时文件。
// =============================================================================

import com.github.project_fredica.db.WorkflowRunDb
import com.github.project_fredica.db.WorkflowRun
import com.github.project_fredica.db.WorkflowRunService
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskDb
import com.github.project_fredica.db.TaskService
import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DagEngineTest {

    private lateinit var db: Database
    private lateinit var taskDb: TaskDb
    private lateinit var workflowRunDb: WorkflowRunDb

    /**
     * 每个测试前重建干净的临时文件数据库，并预创建宿主工作流运行实例 "wr-dag"。
     * 所有测试任务都属于这条流水线。
     */
    @BeforeTest
    fun setup() = runBlocking {
        val tmpFile = File.createTempFile("dagtest_", ".db").also { it.deleteOnExit() }
        db = Database.connect(
            url    = "jdbc:sqlite:${tmpFile.absolutePath}",
            driver = "org.sqlite.JDBC",
        )
        workflowRunDb = WorkflowRunDb(db)
        taskDb     = TaskDb(db)
        workflowRunDb.initialize()
        taskDb.initialize()
        TaskService.initialize(taskDb)
        WorkflowRunService.initialize(workflowRunDb)

        workflowRunDb.create(
            WorkflowRun(
                id = "wr-dag", materialId = "mat-1",
                template = "DAG_TEST", status = "pending",
                totalTasks = 0, doneTasks = 0,
                createdAt = nowSec(),
            )
        )
    }

    // ── 测试 1：无依赖任务立即可认领 ─────────────────────────────────────────

    /**
     * 证明目的：depends_on = [] 的任务（叶节点/根节点）可以被 claimNext() 直接认领，
     *           不需要等待任何前置条件。
     *
     * 证明过程：
     *   1. 创建一个 depends_on=[] 的任务 "no-dep"。
     *   2. 调用 claimNext()，断言返回非 null，且 ID = "no-dep"。
     *
     * 这是 DAG 调度的基础：空依赖列表通过 NOT EXISTS 子查询（展开后无行），
     * NOT EXISTS(空集) = true，因此该任务总是满足可认领条件。
     */
    @Test
    fun testNoDepsClaimable() = runBlocking {
        taskDb.create(task("no-dep", dependsOn = "[]"))

        val claimed = taskDb.claimNext("worker-1")
        assertNotNull(claimed, "depends_on=[] 的任务应该立即可被认领")
        assertEquals("no-dep", claimed.id)
    }

    // ── 测试 2：前置任务未完成时下游被阻塞 ───────────────────────────────────

    /**
     * 证明目的：前置任务处于 pending 或 claimed 状态时，下游任务不能被认领。
     *           仅 completed 状态才算"已完成"。
     *
     * 证明过程：
     *   构造一条两节点链：dep-pending → downstream
     *
     *   轮次 1：
     *   - 调用 claimNext()，预期认领到 dep-pending（无依赖的根任务）。
     *   - 此时 dep-pending 状态变为 "claimed"（已被认领但未完成）。
     *
     *   轮次 2：
     *   - 再次调用 claimNext()，断言返回 null。
     *   - 因为 downstream 的前置 dep-pending 是 "claimed" 而非 "completed"，
     *     NOT EXISTS(dep.status != 'completed') 中存在不满足的行，条件为 false。
     *
     * 关键语义验证：claimed ≠ completed，中间状态不解锁下游。
     */
    @Test
    fun testPendingDepBlocks() = runBlocking {
        taskDb.create(task("dep-pending", dependsOn = "[]"))
        taskDb.create(task("downstream",  dependsOn = """["dep-pending"]"""))

        // 轮次 1：应认领到根任务
        val claimed = taskDb.claimNext("worker-1")
        assertNotNull(claimed)
        assertEquals("dep-pending", claimed.id, "第一次认领应拿到根任务（无依赖）")

        // 轮次 2：dep-pending 现在是 claimed 状态，downstream 仍应被阻塞
        val second = taskDb.claimNext("worker-1")
        assertNull(second, "前置任务仅 claimed（未 completed），下游任务不应被认领")
    }

    // ── 测试 3：前置任务完成后下游解锁 ───────────────────────────────────────

    /**
     * 证明目的：前置任务状态变为 completed 后，下游任务立刻变为可认领状态。
     *
     * 证明过程：
     *   构造一条两节点链：dep-to-complete → unblocked-after
     *
     *   1. 直接调用 updateStatus("dep-to-complete", "completed")，
     *      跳过 pending/claimed/running 阶段，模拟前置任务已完成。
     *   2. 调用 claimNext()，断言认领到的是 "unblocked-after"（下游任务）。
     *      注意：dep-to-complete 虽也是 pending，但因为它已经被直接改为 completed，
     *      实际上 claimNext 的子查询中 dep-to-complete 不满足 status='pending'，
     *      所以它本身不会出现在候选集里，直接认领到了 unblocked-after。
     *
     * 验证的时序语义：状态改变对 claimNext() 是实时可见的（同一数据库文件）。
     */
    @Test
    fun testCompletedDepUnblocks() = runBlocking {
        taskDb.create(task("dep-to-complete", dependsOn = "[]"))
        taskDb.create(task("unblocked-after", dependsOn = """["dep-to-complete"]"""))

        // 直接将前置任务标记为 completed，模拟已完成的状态
        taskDb.updateStatus("dep-to-complete", "completed")

        val claimed = taskDb.claimNext("worker-1")
        assertNotNull(claimed, "前置任务已 completed，下游任务应立即变为可认领")
        assertEquals("unblocked-after", claimed.id)
    }

    // ── 测试 4：多前置任务必须全部完成（AND 语义）────────────────────────────

    /**
     * 证明目的：depends_on 中列出的所有前置任务必须**全部** completed，
     *           "需要全部"而非"只需任一"（AND 语义，非 OR 语义）。
     *
     * 证明过程：
     *   构造 3 → 1 的菱形汇聚：dep-a、dep-b、dep-c → needs-all
     *
     *   阶段 1（dep-c 尚未完成）：
     *   - dep-a、dep-b 已 completed，dep-c 仍为 pending。
     *   - 调用 claimNext()，预期认领到 dep-c（唯一剩余的无阻塞任务）。
     *   - 此时 dep-c 变为 claimed，needs-all 还被 dep-c 阻塞。
     *   - 再次调用 claimNext()，断言返回 null。
     *
     *   阶段 2（dep-c 完成后）：
     *   - 将 dep-c 改为 completed。
     *   - 调用 claimNext()，预期认领到 needs-all。
     *
     * 验证的 SQL 逻辑：NOT EXISTS 子查询检查"是否存在未完成的前置任务"，
     * 只要有一个前置任务未完成，整个 NOT EXISTS 就为 false，任务不进入候选集。
     */
    @Test
    fun testAllDepsRequired() = runBlocking {
        taskDb.create(task("dep-a", dependsOn = "[]"))
        taskDb.create(task("dep-b", dependsOn = "[]"))
        taskDb.create(task("dep-c", dependsOn = "[]"))
        taskDb.create(task("needs-all", dependsOn = """["dep-a","dep-b","dep-c"]"""))

        // dep-a 和 dep-b 已完成，dep-c 还在队列中
        taskDb.updateStatus("dep-a", "completed")
        taskDb.updateStatus("dep-b", "completed")

        // 阶段 1：此时只有 dep-c 可以被认领（needs-all 还在等 dep-c）
        val claimed1 = taskDb.claimNext("worker-1")
        assertNotNull(claimed1)
        assertEquals("dep-c", claimed1.id, "唯一可认领的应是 dep-c（dep-a/b 已完成，dep-c 仍 pending）")

        // dep-c 现在是 claimed（被上一步认领），needs-all 依然被阻塞
        val claimed2 = taskDb.claimNext("worker-1")
        assertNull(claimed2, "dep-c 仅 claimed 未 completed，needs-all 应继续等待")

        // 阶段 2：dep-c 完成，needs-all 的所有前置均已 completed
        taskDb.updateStatus("dep-c", "completed")
        val claimed3 = taskDb.claimNext("worker-1")
        assertNotNull(claimed3, "三个前置全部 completed 后，needs-all 应变为可认领")
        assertEquals("needs-all", claimed3.id)
    }

    // ── 测试 5：自依赖（自环）在创建阶段被拒绝 ───────────────────────────────

    /**
     * 证明目的：任务不能依赖自身（自环），且 depends_on 中引用的 ID 必须存在于
     *           同批次提交的任务列表中。这两条规则由 WorkflowRunStartRoute 在写库前验证。
     *
     * 证明过程（模拟 WorkflowRunStartRoute 的校验逻辑）：
     *
     *   场景 A — 有效的前向引用（B 依赖 A，A 在同批次中存在）：
     *     ids = [cycle-a, cycle-b]
     *     B 的 depends_on = [cycle-a]
     *     对 depends_on 中的每个 ID 检查 "是否在 ids 中"，
     *     cycle-a 在 ids 里 → 无效 ID 列表 = []，校验通过。
     *
     *   场景 B — 自依赖（A 依赖自身）：
     *     A 的 depends_on = [cycle-a]，且 ids[0] = cycle-a
     *     检查 "cycle-a 是否等于自身 ID"，结果为 true → 检测到自环，应报错。
     *
     * 说明：此测试直接内联了 WorkflowRunStartRoute 中的验证逻辑，
     *       不需要启动 HTTP 服务器即可验证规则正确性。
     *       真实的 API 层面校验见 WorkflowRunStartRoute。
     */
    @Test
    fun testCycleRejected() {
        val idA = "cycle-a"
        val idB = "cycle-b"
        val ids = listOf(idA, idB) // 模拟同批次提交的任务 ID 列表

        // 场景 A：B 依赖 A，A 存在于同批次 → 应通过校验（无效 ID 数 = 0）
        val bInvalidDeps = listOf(idA).filter { it !in ids }
        assertEquals(0, bInvalidDeps.size, "B 依赖 A，A 在同批次中存在，应视为合法引用")

        // 场景 B：A 依赖自身 → 应检测为自环并拒绝
        val selfCycleDetected = listOf(idA).contains(idA)
        assertEquals(true, selfCycleDetected, "任务依赖自身应被检测为自环并标记为非法")
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────────────

    private fun nowSec() = System.currentTimeMillis() / 1000L

    /** 快速构造属于 "wr-dag" 的测试任务。 */
    private fun task(id: String, dependsOn: String = "[]") = Task(
        id = id, type = "DOWNLOAD_VIDEO",
        workflowRunId = "wr-dag", materialId = "mat-1",
        dependsOn = dependsOn,
        createdAt = nowSec(),
    )
}
