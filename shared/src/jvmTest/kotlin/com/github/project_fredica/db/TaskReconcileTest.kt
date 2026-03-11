package com.github.project_fredica.db

// =============================================================================
//  TaskReconcileTest —— Task 层三道对账保护机制的可靠性测试
// =============================================================================
//
//  ┌─────────────────────────────────────────────────────────────────────────┐
//  │                    Task 层三道对账保护链                                 │
//  │                                                                         │
//  │  场景                    │ 保护方法                │ 测试分组            │
//  │ ─────────────────────── │ ─────────────────────── │ ─────────────────  │
//  │  APP 强杀（running/      │ resetStaleTasks()       │  Group A (5 cases) │
//  │  claimed 状态僵死）       │ WorkerEngine 启动时调用  │                    │
//  │ ─────────────────────── │ ─────────────────────── │ ─────────────────  │
//  │  WorkflowRun 记录被删除  │ failOrphanedTasks()     │  Group B (4 cases) │
//  │  （孤立 Task 永远无法完成）│ WorkerEngine 启动时调用  │                    │
//  │ ─────────────────────── │ ─────────────────────── │ ─────────────────  │
//  │  用户主动取消工作流       │ cancelPendingTasks      │  Group C (5 cases) │
//  │  （等待中任务应一并取消） │ ByWorkflowRun()         │                    │
//  └─────────────────────────────────────────────────────────────────────────┘
//
//  【自查发现的问题（2026-03-10 修正）】
//
//  ① setTaskStatus helper 未写 claimed_at：
//      A2 原来只验证了 claimed_at: null→null 的平凡清空（无意义）。
//      修复：setTaskStatus 增加 claimedAt 参数；A2 补写 claimed_at 的真实断言。
//
//  ② Group B 缺少对 running/claimed 孤立任务的测试（B4 新增）：
//      B1 只测 pending 状态孤立任务。但 APP 强杀场景最危险的恰好是
//      停在 running/claimed 的孤立任务——必须确认 failOrphanedTasks 也能覆盖。
//
//  ③ C1 缺少 completedAt 断言：
//      实现写了 completed_at=COALESCE(completed_at,?)，但测试没有断言。
//
//  ④ 启动恢复链路组合场景未测试（A5 新增）：
//      claimed 孤立任务在 APP 重启时经历两步：
//        resetStaleTasks → claimed→pending（认领未开始，可重新入队）
//        failOrphanedTasks → pending→failed（WF 不存在，永远无法推进）
//      两步各自有单测，但组合链路未端到端验证。
//
//  【不测什么】
//    - claimNext() 的 DAG 调度逻辑（→ TaskDbTest.testAtomicClaim）
//    - updateStatus / updateProgress 的持久化（→ TaskDbTest）
//    - WorkflowRun 层的汇总状态推导（→ WorkflowRunReconcileTest）
//
//  【测试环境】
//    每个 @Test 使用独立的 SQLite 临时文件，完全隔离，@BeforeTest 重建表结构。
// =============================================================================

import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskReconcileTest {

    private lateinit var db: Database
    private lateinit var taskDb: TaskDb
    private lateinit var workflowRunDb: WorkflowRunDb

    @BeforeTest
    fun setup() = runBlocking {
        val tmp = File.createTempFile("task_reconcile_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmp.absolutePath}", driver = "org.sqlite.JDBC")
        workflowRunDb = WorkflowRunDb(db)
        taskDb = TaskDb(db)
        workflowRunDb.initialize()
        taskDb.initialize()
        TaskService.initialize(taskDb)
        WorkflowRunService.initialize(workflowRunDb)
    }

    // =========================================================================
    //  Group A: resetStaleTasks — APP 强杀后的启动恢复
    // =========================================================================
    //
    //  ┌─────────────────────────────────────────────────────────────────────┐
    //  │  【可靠性保证】                                                       │
    //  │  APP 随时可能被操作系统强杀（内存不足、用户强制退出、崩溃等）。强杀   │
    //  │  发生时正在执行的 Task 停在 running 状态、已认领但未开始的 Task      │
    //  │  停在 claimed 状态，没有任何代码能够把它们推进到终态。               │
    //  │                                                                     │
    //  │  WorkerEngine 在重启后第一件事就是调用 resetStaleTasks()，           │
    //  │  确保这些"僵尸任务"在新一轮调度开始之前得到妥善处理：               │
    //  │    - running → cancelled（执行被中断，取消语义更准确，不触发 WF 失败）│
    //  │    - claimed → cancelled（仅预留未执行，同样取消）                   │
    //  │    - pending → cancelled（上游已取消，DAG 永久阻塞，一并取消）       │
    //  │                                                                     │
    //  │  【如果缺少此保护会怎样】                                             │
    //  │    running 任务永久卡在 running，claimNext() 永远不再认领它，        │
    //  │    工作流进度条停止，前端显示"进行中"但实际已无 Worker 处理。        │
    //  └─────────────────────────────────────────────────────────────────────┘

    /**
     * A1: running 任务 → cancelled，且 error/error_type 字段正确写入。
     *
     * 验证：APP 强杀后 running 任务被标记为 cancelled（外部中断，非任务自身错误），
     * 调用方通过 error_type="APP_RESTART" 可区分"正常取消"与"强杀中断"，便于监控。
     */
    @Test
    fun testResetStaleTasks_runningToFailed() = runBlocking {
        println("[GUARANTEE] running 任务在 APP 重启后必须被标记为 cancelled，不能永远停留在 running")

        val wf = makeWf("wf-A1")
        val t = makeTask("A1-run", wf.id, status = "running")

        println("[ARRANGE] wf=${wf.id}, task=${t.id} status=running")
        workflowRunDb.create(wf)
        taskDb.create(t)

        println("[ACT] resetStaleTasks()")
        val affected = taskDb.resetStaleTasks()
        println("[AFTER] affectedWfIds=$affected")

        val updated = taskDb.findById(t.id)!!
        println("[ASSERT] task status=${updated.status}, error=${updated.error}, errorType=${updated.errorType}")

        assertEquals("cancelled", updated.status,
            "running 任务在 APP 重启后变为 cancelled（外部中断，取消语义比 failed 更准确）")
        assertEquals("APP_RESTARTED", updated.error,
            "error 字段应标注强杀原因，便于区分正常取消")
        assertEquals("APP_RESTART", updated.errorType,
            "error_type 用于监控分类，APP_RESTART 与业务错误区分开来")
        assertTrue(updated.completedAt != null,
            "cancelled 终态应记录 completed_at 时间戳")
        assertTrue(wf.id in affected,
            "受影响工作流 ID 必须出现在返回集合中，供调用方触发 recalculate")

        println("[RESULT] ✓ running→cancelled 成功，error_type=APP_RESTART")
    }

    /**
     * A2: claimed 任务 → cancelled（APP 强杀，已认领但未开始）。
     *
     * 验证：claimed 状态代表"已被认领但尚未开始执行"，APP 重启后取消此任务
     * （而非退回 pending），防止被新 Worker 重新执行导致重复处理风险。
     *
     * 注意：resetStaleTasks 对 claimed 任务只修改 status 和 completed_at，
     * 不清空 claimed_by / claimed_at 字段（历史信息保留用于审计）。
     */
    @Test
    fun testResetStaleTasks_claimedToPending() = runBlocking {
        println("[GUARANTEE] claimed 任务在 APP 重启后取消（claimed 未执行，但取消比退 pending 更保守）")

        val wf = makeWf("wf-A2")
        val t = makeTask("A2-claimed", wf.id, status = "claimed")
        workflowRunDb.create(wf)
        taskDb.create(t)

        // 写入完整的 claimed 字段（包含 claimed_at），模拟 claimNext 真实认领后的状态
        val claimedAtBefore = nowSec()
        setTaskStatus(t.id, "claimed", claimedBy = "worker-old", claimedAt = claimedAtBefore)
        println("[ARRANGE] task=${t.id} status=claimed, claimed_by=worker-old, claimed_at=$claimedAtBefore")

        println("[ACT] resetStaleTasks()")
        taskDb.resetStaleTasks()

        val updated = taskDb.findById(t.id)!!
        println("[AFTER] task status=${updated.status}, completedAt=${updated.completedAt}")

        assertEquals("cancelled", updated.status,
            "claimed 任务应被取消（APP 重启清理，取消而非退 pending，避免重复执行）")
        assertTrue(updated.completedAt != null,
            "cancelled 终态应记录 completed_at 时间戳")

        println("[RESULT] ✓ claimed→cancelled 成功，completedAt 已记录")
    }

    /**
     * A3: running/claimed → cancelled；pending → cancelled；completed/failed/cancelled 不受影响。
     *
     * 验证：resetStaleTasks() 具备精确的状态过滤——
     *   - running/claimed/pending（所有非终态）→ cancelled
     *   - completed/failed/cancelled（已是终态）→ 不受影响（幂等）
     */
    @Test
    fun testResetStaleTasks_onlyAffectsStalStates() = runBlocking {
        println("[GUARANTEE] resetStaleTasks 对非终态任务全部取消，对终态任务幂等")

        val wf = makeWf("wf-A3")
        workflowRunDb.create(wf)

        val pending   = makeTask("A3-pending",   wf.id, status = "pending")
        val completed = makeTask("A3-completed", wf.id, status = "pending")
        val failed    = makeTask("A3-failed",    wf.id, status = "pending")
        val cancelled = makeTask("A3-cancelled", wf.id, status = "pending")
        val running   = makeTask("A3-running",   wf.id, status = "pending")
        val claimed   = makeTask("A3-claimed",   wf.id, status = "pending")

        listOf(pending, completed, failed, cancelled, running, claimed).forEach { taskDb.create(it) }

        // 手动设置各任务的终态（使用 updateStatus 让时间戳也正确写入）
        taskDb.updateStatus(completed.id, "completed")
        taskDb.updateStatus(failed.id,    "failed",    error = "手动失败")
        taskDb.updateStatus(cancelled.id, "cancelled")
        setTaskStatus(running.id,  "running")
        setTaskStatus(claimed.id,  "claimed")

        println("[ARRANGE] 6个任务：pending/completed/failed/cancelled/running/claimed")
        println("[ACT] resetStaleTasks()")
        taskDb.resetStaleTasks()

        val after = mapOf(
            "pending"   to taskDb.findById(pending.id)!!.status,
            "completed" to taskDb.findById(completed.id)!!.status,
            "failed"    to taskDb.findById(failed.id)!!.status,
            "cancelled" to taskDb.findById(cancelled.id)!!.status,
            "running"   to taskDb.findById(running.id)!!.status,
            "claimed"   to taskDb.findById(claimed.id)!!.status,
        )
        println("[AFTER] $after")

        assertEquals("cancelled", after["pending"],   "pending → cancelled（上游取消，DAG 永久阻塞）")
        assertEquals("completed", after["completed"], "completed 终态任务不受影响")
        assertEquals("failed",    after["failed"],    "failed 终态任务不受影响")
        assertEquals("cancelled", after["cancelled"], "cancelled 终态任务不受影响（幂等）")
        assertEquals("cancelled", after["running"],   "running → cancelled（强杀中断）")
        assertEquals("cancelled", after["claimed"],   "claimed → cancelled（强杀中断）")

        println("[RESULT] ✓ 非终态任务均变为 cancelled，completed/failed/已cancelled 保持不变")
    }

    /**
     * A4: 返回受影响的 workflowRunId 集合，且仅包含真正有任务被修改的 WF。
     *
     * 验证：调用方（WorkerEngine）需要根据此集合触发 recalculate()，
     * 只有包含 running/claimed 任务的 WF 才需要重算，其余 WF 不应出现在集合中。
     */
    @Test
    fun testResetStaleTasks_returnsCorrectWfIds() = runBlocking {
        println("[GUARANTEE] resetStaleTasks 必须返回精确的受影响 WF 集合，避免多余的 recalculate 开销")

        val wf1 = makeWf("wf-A4-stale")   // 含僵尸任务
        val wf2 = makeWf("wf-A4-clean")   // 全部是终态任务，不应出现
        workflowRunDb.create(wf1)
        workflowRunDb.create(wf2)

        val staleTask   = makeTask("A4-running",   wf1.id, status = "pending")
        val cleanTask   = makeTask("A4-completed", wf2.id, status = "pending")
        taskDb.create(staleTask)
        taskDb.create(cleanTask)

        setTaskStatus(staleTask.id,  "running")
        taskDb.updateStatus(cleanTask.id, "completed")

        println("[ARRANGE] wf1 含 running 任务, wf2 只含 completed 任务")
        println("[ACT] resetStaleTasks()")
        val affected = taskDb.resetStaleTasks()
        println("[AFTER] affectedWfIds=$affected")

        assertTrue(wf1.id in affected,
            "含 running 任务的 wf1 必须出现在返回集合中")
        assertTrue(wf2.id !in affected,
            "只含 completed 任务的 wf2 不应出现在返回集合（没有需要 recalculate 的变化）")

        println("[RESULT] ✓ 返回集合精确包含 wf1，不含 wf2")
    }

    /**
     * A5: 启动恢复链路组合验证——claimed 孤立任务经 resetStaleTasks 直接变为 cancelled。
     *
     * 场景：APP 强杀时一个 Task 处于 claimed 状态，且其 WorkflowRun 已被删除（数据不一致）。
     *
     * 新行为（单步）：
     *   resetStaleTasks()    → claimed → cancelled（直接取消，不经过 pending）
     *
     * 之后 failOrphanedTasks() 不再需要处理此任务（已是终态 cancelled）。
     *
     * 【为什么不需要两步】
     *   旧设计：claimed → pending（resetStaleTasks）→ failed（failOrphanedTasks）
     *   新设计：claimed → cancelled（resetStaleTasks），一步到位进入终态；
     *          failOrphanedTasks 的 SQL 过滤 NOT IN ('completed','failed','cancelled')，
     *          所以 cancelled 任务不会被重复处理。
     */
    @Test
    fun testStartupRecovery_orphanedClaimedTask_endUpFailed() = runBlocking {
        println("[SCENARIO] APP 强杀 + WF 已删除：claimed 孤立任务一步变为 cancelled")
        println("[CHAIN] resetStaleTasks: claimed→cancelled（直接终态，不经过 pending）")

        val wf = makeWf("wf-A5")
        workflowRunDb.create(wf)
        val t = makeTask("A5-orphan-claimed", wf.id, status = "pending")
        taskDb.create(t)

        // 模拟 claimNext 认领后的完整状态
        setTaskStatus(t.id, "claimed", claimedBy = "worker-1", claimedAt = nowSec())
        // 模拟 WF 被手动删除（数据不一致）
        deleteWorkflowRun(wf.id)

        println("[ARRANGE] task A5 status=claimed (orphaned, WF deleted)")
        assertEquals("claimed", taskDb.findById(t.id)!!.status, "测试前确认状态为 claimed")

        println("[ACT] resetStaleTasks()")
        taskDb.resetStaleTasks()
        val afterReset = taskDb.findById(t.id)!!
        println("[AFTER resetStaleTasks] task status=${afterReset.status}（期望: cancelled）")
        assertEquals("cancelled", afterReset.status,
            "claimed 任务在 resetStaleTasks 后直接变为 cancelled（不经过 pending，一步终态）")
        assertTrue(afterReset.completedAt != null,
            "cancelled 终态应记录 completed_at")

        println("[ACT] failOrphanedTasks()（验证 cancelled 任务不被重复处理）")
        val orphaned = taskDb.failOrphanedTasks()
        val afterOrphaned = taskDb.findById(t.id)!!
        println("[AFTER failOrphanedTasks] task status=${afterOrphaned.status}（期望: 仍为 cancelled）")

        assertTrue(t.id !in orphaned, "已是 cancelled 终态的任务不应被 failOrphanedTasks 再次处理")
        assertEquals("cancelled", afterOrphaned.status,
            "cancelled 是终态，failOrphanedTasks 的 SQL 过滤跳过终态任务，状态保持不变")

        println("[RESULT] ✓ claimed 孤立任务一步变为 cancelled，failOrphanedTasks 正确跳过终态任务")
        println("[PRINCIPLE] 新设计：cancelled 是终态，无需 failOrphanedTasks 二次处理")
    }

    // =========================================================================
    //  Group B: failOrphanedTasks — 孤立任务对账
    // =========================================================================
    //
    //  ┌─────────────────────────────────────────────────────────────────────┐
    //  │  【可靠性保证】                                                       │
    //  │  WorkflowRun 记录可能因数据迁移、手动 DB 清理或 bug 被意外删除。    │
    //  │  删除后，其 Task 成为"孤立任务"——workflow_run_id 指向不存在的记录。  │
    //  │                                                                     │
    //  │  孤立任务的后果：                                                    │
    //  │    - claimNext() 可能认领它（pending 状态），但 recalculate() 找不到  │
    //  │      对应 WF，工作流整体进度永远更新不了                            │
    //  │    - running 状态的孤立任务永远不会有 Worker 继续执行                │
    //  │    - 前端看到的任务列表出现"来路不明"的任务                        │
    //  │                                                                     │
    //  │  failOrphanedTasks() 在启动时一次性标记所有孤立非终态 Task 为 failed，│
    //  │  保证 Task 表没有悬空引用的非终态数据。                             │
    //  └─────────────────────────────────────────────────────────────────────┘

    /**
     * B1: WorkflowRun 被删除后，非终态的孤立 Task 应被标记为 failed。
     *
     * 验证：failOrphanedTasks() 正确识别"workflow_run_id 指向已不存在 WF"的 Task
     * 并批量标记失败，error_type="ORPHANED" 便于区分正常失败。
     */
    @Test
    fun testFailOrphanedTasks_marksOrphanedTasksAsFailed() = runBlocking {
        println("[GUARANTEE] WF 被删除后，其 Task 必须通过 failOrphanedTasks 标记失败，不能永远处于 pending")

        // 创建 WF 和 Task
        val wf = makeWf("wf-B1")
        workflowRunDb.create(wf)
        val t1 = makeTask("B1-t1", wf.id, status = "pending")
        val t2 = makeTask("B1-t2", wf.id, status = "pending")
        taskDb.create(t1); taskDb.create(t2)

        println("[ARRANGE] wf=${wf.id} 及 2 个 pending task，然后直接删除 WF 记录")

        // 直接删除 WF 记录（模拟手动清理或数据迁移导致的数据不一致）
        deleteWorkflowRun(wf.id)
        println("[SCENARIO] WF 记录已删除，t1/t2 成为孤立任务（workflow_run_id 无对应记录）")

        println("[ACT] failOrphanedTasks()")
        val orphaned = taskDb.failOrphanedTasks()
        println("[AFTER] 被标记的孤立任务=$orphaned")

        assertTrue(t1.id in orphaned, "孤立任务 t1 应出现在返回列表中")
        assertTrue(t2.id in orphaned, "孤立任务 t2 应出现在返回列表中")

        val t1After = taskDb.findById(t1.id)!!
        val t2After = taskDb.findById(t2.id)!!
        println("[ASSERT] t1 status=${t1After.status} errorType=${t1After.errorType}")
        println("[ASSERT] t2 status=${t2After.status} errorType=${t2After.errorType}")

        assertEquals("failed", t1After.status, "孤立任务 t1 应被标记为 failed")
        assertEquals("failed", t2After.status, "孤立任务 t2 应被标记为 failed")
        assertEquals("ORPHANED", t1After.errorType,
            "error_type=ORPHANED 便于与业务失败区分，用于监控统计")

        println("[RESULT] ✓ 2 个孤立任务均成功标记为 failed，error_type=ORPHANED")
    }

    /**
     * B4: running / claimed 状态的孤立 Task 也应被 failOrphanedTasks 标记为 failed。
     *
     * 【自查补充】B1 只测试了 pending 孤立任务。但生产中 APP 强杀后最危险的恰好是
     * 停在 running/claimed 的孤立任务——WF 已被删除，Worker 也不再运行，
     * 没有任何机制能把它们推进到终态，前端会永远看到"进行中"。
     *
     * 注意：此测试不依赖 resetStaleTasks 的前置处理——纯粹验证
     * failOrphanedTasks 能独立处理 running/claimed 孤立任务。
     *
     * 【与 A5 的区别】
     *   A5 验证的是"resetStaleTasks → failOrphanedTasks"的两步链路（claimed 先→pending 再→failed）
     *   B4 验证的是 failOrphanedTasks 单独调用时，直接处理 running 和 claimed 孤立任务
     */
    @Test
    fun testFailOrphanedTasks_handlesRunningAndClaimedStates() = runBlocking {
        println("[GUARANTEE] failOrphanedTasks 覆盖 running 和 claimed 两种孤立任务状态，不只是 pending")

        val wf = makeWf("wf-B4")
        workflowRunDb.create(wf)

        val tRunning = makeTask("B4-running", wf.id, status = "pending")
        val tClaimed = makeTask("B4-claimed", wf.id, status = "pending")
        taskDb.create(tRunning); taskDb.create(tClaimed)

        setTaskStatus(tRunning.id, "running")
        setTaskStatus(tClaimed.id, "claimed", claimedBy = "worker-2", claimedAt = nowSec())

        deleteWorkflowRun(wf.id)
        println("[ARRANGE] tRunning status=running（orphaned），tClaimed status=claimed（orphaned）")
        println("[SCENARIO] WF 已删除，两个非终态孤立任务永远无法被 recalculate 或 claimNext 正常推进")

        println("[ACT] failOrphanedTasks()")
        val orphaned = taskDb.failOrphanedTasks()
        println("[AFTER] 被标记的孤立任务=$orphaned")

        assertTrue(tRunning.id in orphaned, "running 孤立任务应被 failOrphanedTasks 捕获")
        assertTrue(tClaimed.id in orphaned, "claimed 孤立任务应被 failOrphanedTasks 捕获")

        val runningAfter = taskDb.findById(tRunning.id)!!
        val claimedAfter = taskDb.findById(tClaimed.id)!!
        println("[ASSERT] running孤立 status=${runningAfter.status}, errorType=${runningAfter.errorType}")
        println("[ASSERT] claimed孤立 status=${claimedAfter.status}, errorType=${claimedAfter.errorType}")

        assertEquals("failed",   runningAfter.status,   "running 孤立任务 → failed")
        assertEquals("ORPHANED", runningAfter.errorType, "error_type=ORPHANED")
        assertEquals("failed",   claimedAfter.status,   "claimed 孤立任务 → failed")
        assertEquals("ORPHANED", claimedAfter.errorType, "error_type=ORPHANED")

        println("[RESULT] ✓ running/claimed 孤立任务均成功标记为 failed")
        println("[DEFENSE] SQL: status NOT IN ('completed','failed','cancelled') 覆盖所有非终态")
    }

    /**
     * B2: 有效 WorkflowRun 下的 Task 不受 failOrphanedTasks 影响。
     *
     * 验证：failOrphanedTasks() 只处理孤立任务，有对应 WF 的任务保持不变，
     * 防止误伤正常任务（精确过滤）。
     */
    @Test
    fun testFailOrphanedTasks_doesNotAffectValidTasks() = runBlocking {
        println("[GUARANTEE] failOrphanedTasks 具备精确过滤，有效 WF 的 Task 不受影响")

        val wf = makeWf("wf-B2")
        workflowRunDb.create(wf)
        val t = makeTask("B2-valid", wf.id, status = "pending")
        taskDb.create(t)

        println("[ARRANGE] wf=${wf.id} 及 1 个 pending task，WF 记录保留（不删除）")
        println("[ACT] failOrphanedTasks()")
        val orphaned = taskDb.failOrphanedTasks()
        println("[AFTER] 被标记的孤立任务=$orphaned（应为空）")

        assertTrue(orphaned.isEmpty(), "没有孤立任务时，返回列表应为空")
        assertEquals("pending", taskDb.findById(t.id)!!.status,
            "有效 WF 下的 pending task 不应被 failOrphanedTasks 修改")

        println("[RESULT] ✓ 有效任务保持 pending，未被误标记")
    }

    /**
     * B3: 已是终态的孤立 Task 不被重复处理（幂等性验证）。
     *
     * 验证：孤立的 completed/failed/cancelled Task 已无需处理，
     * failOrphanedTasks() 不会重复写入、不会更改已有的 result 和 error 字段。
     *
     * 【陷阱】若没有此保护，重复执行 failOrphanedTasks 可能覆盖已有的业务错误信息。
     */
    @Test
    fun testFailOrphanedTasks_idempotentForTerminalTasks() = runBlocking {
        println("[GUARANTEE] failOrphanedTasks 对终态孤立任务幂等，不会覆盖已有的 error 信息")

        val wf = makeWf("wf-B3")
        workflowRunDb.create(wf)

        val t = makeTask("B3-done", wf.id, status = "pending")
        taskDb.create(t)
        taskDb.updateStatus(t.id, "failed", error = "ORIGINAL_ERROR", errorType = "BUSINESS_ERROR")

        // 删除 WF → t 变为孤立任务，但已是终态 failed
        deleteWorkflowRun(wf.id)
        println("[ARRANGE] 孤立 task B3-done 已处于 failed 终态（errorType=BUSINESS_ERROR）")

        println("[ACT] failOrphanedTasks()")
        val orphaned = taskDb.failOrphanedTasks()
        println("[AFTER] 被标记的孤立任务=$orphaned（应为空，终态任务不应被处理）")

        assertTrue(orphaned.isEmpty(),
            "终态孤立任务不应被 failOrphanedTasks 重复处理")

        val after = taskDb.findById(t.id)!!
        assertEquals("BUSINESS_ERROR", after.errorType,
            "已有的 error_type 不应被覆盖为 ORPHANED")
        assertEquals("ORIGINAL_ERROR", after.error,
            "已有的 error 信息不应被覆盖")

        println("[RESULT] ✓ 终态孤立任务未被处理，error 信息保持原样")
    }

    // =========================================================================
    //  Group C: cancelPendingTasksByWorkflowRun — 级联取消等待中的任务
    // =========================================================================
    //
    //  ┌─────────────────────────────────────────────────────────────────────┐
    //  │  【可靠性保证】                                                       │
    //  │  用户取消一个 WorkflowRun 时，WorkflowRun 本身的 status 变为        │
    //  │  cancelled，但尚在队列中等待的 Task 仍处于 pending/claimed 状态。  │
    //  │  若不一并取消，这些任务会继续被 claimNext() 认领并执行——浪费资源    │
    //  │  且产生用户已取消但仍在处理的矛盾状态。                             │
    //  │                                                                     │
    //  │  cancelPendingTasksByWorkflowRun() 解决此问题：                     │
    //  │    - pending/claimed → cancelled（等待中的任务一并取消）             │
    //  │    - running 任务不处理（必须通过 TaskCancelService 发取消信号）     │
    //  │    - 终态任务不处理（幂等安全）                                     │
    //  └─────────────────────────────────────────────────────────────────────┘

    /**
     * C1: pending 任务在工作流取消时应变为 cancelled。
     *
     * 验证：用户取消工作流后，pending 任务不再被 claimNext 认领和执行。
     */
    @Test
    fun testCancelPending_pendingTasks() = runBlocking {
        println("[GUARANTEE] WorkflowRun 取消时，pending 任务必须级联取消，防止被误认领")

        val wf = makeWf("wf-C1")
        workflowRunDb.create(wf)
        val t1 = makeTask("C1-t1", wf.id, status = "pending")
        val t2 = makeTask("C1-t2", wf.id, status = "pending")
        taskDb.create(t1); taskDb.create(t2)

        println("[ARRANGE] 2 个 pending 任务属于 wf-C1")
        println("[ACT] cancelPendingTasksByWorkflowRun(wf-C1)")
        val cancelled = taskDb.cancelPendingTasksByWorkflowRun(wf.id)
        println("[AFTER] 被取消的任务=$cancelled")

        assertEquals(2, cancelled.size, "2 个 pending 任务均应被取消")
        assertTrue(t1.id in cancelled && t2.id in cancelled, "两个任务 ID 都应出现在返回列表中")

        assertEquals("cancelled", taskDb.findById(t1.id)!!.status, "t1 应变为 cancelled")
        assertEquals("cancelled", taskDb.findById(t2.id)!!.status, "t2 应变为 cancelled")
        // 【自查补充】cancelled 是终态，应记录 completed_at（实现写了 COALESCE，但测试原来没断言）
        assertTrue(taskDb.findById(t1.id)!!.completedAt != null,
            "cancelled 终态任务应记录 completed_at 时间戳，便于统计任务等待时长")

        println("[RESULT] ✓ 2 个 pending 任务成功取消")
    }

    /**
     * C2: claimed 任务（已认领但未执行）在工作流取消时应变为 cancelled。
     *
     * 验证：claimed 状态代表"已被 Worker 预占但尚未调用 execute()"，
     * 此时取消是安全的，因为没有任何副作用产生。
     */
    @Test
    fun testCancelPending_claimedTasks() = runBlocking {
        println("[GUARANTEE] claimed 任务（已预占未执行）取消时也必须被级联取消")

        val wf = makeWf("wf-C2")
        workflowRunDb.create(wf)
        val t = makeTask("C2-claimed", wf.id, status = "pending")
        taskDb.create(t)
        setTaskStatus(t.id, "claimed", claimedBy = "worker-1")

        println("[ARRANGE] task C2-claimed 状态=claimed（已被 worker-1 认领但未开始执行）")
        println("[ACT] cancelPendingTasksByWorkflowRun(wf-C2)")
        val cancelled = taskDb.cancelPendingTasksByWorkflowRun(wf.id)
        println("[AFTER] 被取消的任务=$cancelled")

        assertTrue(t.id in cancelled, "claimed 任务也应被取消")
        assertEquals("cancelled", taskDb.findById(t.id)!!.status, "claimed 任务应变为 cancelled")

        println("[RESULT] ✓ claimed 任务成功取消（无副作用，安全）")
    }

    /**
     * C3: running 任务不受影响（需通过 TaskCancelService 发取消信号）。
     *
     * 验证：running 任务已被 Executor 持有，直接改 DB 状态会导致 Executor 与
     * DB 不一致——Executor 写 completed 时会覆盖我们刚写的 cancelled。
     * 正确做法是通过 TaskCancelService.cancel(taskId) 发信号让 Executor 自行退出。
     *
     * 【设计权衡】：running 任务由 cancelPendingTasksByWorkflowRun 跳过，
     * 调用方负责对 running 任务额外调用 TaskCancelService.cancel(id)。
     */
    @Test
    fun testCancelPending_doesNotAffectRunningTasks() = runBlocking {
        println("[TRADEOFF] running 任务不由 cancelPendingTasksByWorkflowRun 处理")
        println("[CONTRACT] running 任务的取消必须通过 TaskCancelService.cancel(taskId) 信号机制")

        val wf = makeWf("wf-C3")
        workflowRunDb.create(wf)
        val t = makeTask("C3-running", wf.id, status = "pending")
        taskDb.create(t)
        setTaskStatus(t.id, "running")

        println("[ARRANGE] task C3-running 状态=running（Executor 正在执行）")
        println("[ACT] cancelPendingTasksByWorkflowRun(wf-C3)")
        val cancelled = taskDb.cancelPendingTasksByWorkflowRun(wf.id)
        println("[AFTER] 被取消的任务=$cancelled（应为空）")

        assertTrue(cancelled.isEmpty(),
            "running 任务不应被级联取消（Executor 正在执行，直接修改 DB 状态会导致状态不一致）")
        assertEquals("running", taskDb.findById(t.id)!!.status,
            "running 状态不应被修改")

        println("[RESULT] ✓ running 任务保持不变，需另行通过 TaskCancelService 处理")
    }

    /**
     * C4: 只取消指定 WorkflowRun 下的任务，不影响其他 WF 的任务。
     *
     * 验证：cancelPendingTasksByWorkflowRun 具备精确的 WF 隔离，
     * 多个 WF 并发存在时不会误取消其他 WF 的任务。
     */
    @Test
    fun testCancelPending_isolatesWorkflow() = runBlocking {
        println("[GUARANTEE] 取消操作必须严格隔离到目标 WF，不能波及其他工作流")

        val wf1 = makeWf("wf-C4-cancel")  // 要取消的 WF
        val wf2 = makeWf("wf-C4-keep")    // 保留的 WF
        workflowRunDb.create(wf1); workflowRunDb.create(wf2)

        val t1 = makeTask("C4-t1", wf1.id, status = "pending")
        val t2 = makeTask("C4-t2", wf2.id, status = "pending")
        taskDb.create(t1); taskDb.create(t2)

        println("[ARRANGE] wf1(${wf1.id}) 有 pending 任务 t1，wf2(${wf2.id}) 有 pending 任务 t2")
        println("[ACT] cancelPendingTasksByWorkflowRun(${wf1.id})")
        taskDb.cancelPendingTasksByWorkflowRun(wf1.id)

        val t1After = taskDb.findById(t1.id)!!.status
        val t2After = taskDb.findById(t2.id)!!.status
        println("[AFTER] t1(${wf1.id}) status=$t1After, t2(${wf2.id}) status=$t2After")

        assertEquals("cancelled", t1After, "wf1 的任务 t1 应被取消")
        assertEquals("pending",   t2After, "wf2 的任务 t2 不应受影响，必须保持 pending")

        println("[RESULT] ✓ 取消精确隔离到 wf1，wf2 的 t2 保持 pending")
    }

    /**
     * C5: 终态任务（completed/failed/cancelled）不受 cancelPendingTasksByWorkflowRun 影响。
     *
     * 验证：幂等性——对一个已经 completed 或 failed 的任务调用级联取消，
     * 不应改变其状态或覆盖已有的 result/error 信息。
     */
    @Test
    fun testCancelPending_idempotentForTerminalTasks() = runBlocking {
        println("[GUARANTEE] 级联取消对终态任务幂等，不覆盖已有 result/error 数据")

        val wf = makeWf("wf-C5")
        workflowRunDb.create(wf)

        val tCompleted = makeTask("C5-completed", wf.id, status = "pending")
        val tFailed    = makeTask("C5-failed",    wf.id, status = "pending")
        val tCancelled = makeTask("C5-cancelled", wf.id, status = "pending")
        listOf(tCompleted, tFailed, tCancelled).forEach { taskDb.create(it) }

        taskDb.updateStatus(tCompleted.id, "completed", result = """{"done":true}""")
        taskDb.updateStatus(tFailed.id,    "failed",    error = "REAL_FAILURE")
        taskDb.updateStatus(tCancelled.id, "cancelled")

        println("[ARRANGE] 3 个终态任务（completed/failed/cancelled），无 pending 任务")
        println("[ACT] cancelPendingTasksByWorkflowRun(${wf.id})")
        val cancelled = taskDb.cancelPendingTasksByWorkflowRun(wf.id)
        println("[AFTER] 被取消的任务=$cancelled（应为空）")

        assertTrue(cancelled.isEmpty(), "终态任务不应被重复取消")
        assertEquals("completed", taskDb.findById(tCompleted.id)!!.status)
        assertEquals("failed",    taskDb.findById(tFailed.id)!!.status)
        assertEquals("cancelled", taskDb.findById(tCancelled.id)!!.status)
        assertEquals("REAL_FAILURE", taskDb.findById(tFailed.id)!!.error,
            "已有的 error 信息不应被覆盖")

        println("[RESULT] ✓ 终态任务保持不变，error 信息未被覆盖")
    }

    // =========================================================================
    //  辅助方法
    // =========================================================================

    private fun nowSec() = System.currentTimeMillis() / 1000L

    private fun makeWf(id: String) = WorkflowRun(
        id = id, materialId = "mat-1", template = "TEST",
        status = "pending", totalTasks = 0, doneTasks = 0, createdAt = nowSec()
    )

    private fun makeTask(id: String, wfId: String, status: String = "pending") = Task(
        id = id, type = "TEST_TASK", workflowRunId = wfId,
        materialId = "mat-1", status = status, createdAt = nowSec()
    )

    /**
     * 直接操作 DB 设置 Task 状态（绕过 updateStatus 的时间戳逻辑，用于精确模拟中间态）。
     *
     * @param claimedAt  认领时间戳（Unix 秒）；设置 claimed/running 状态时传入，模拟真实认领场景；
     *                   resetStaleTasks 应将此字段清空（→ null），A2 对此有显式断言。
     */
    private fun setTaskStatus(id: String, status: String, claimedBy: String? = null, claimedAt: Long? = null) {
        db.useConnection { conn ->
            conn.prepareStatement(
                "UPDATE task SET status=?, claimed_by=?, claimed_at=? WHERE id=?"
            ).use { ps ->
                ps.setString(1, status)
                ps.setString(2, claimedBy)
                if (claimedAt != null) ps.setLong(3, claimedAt) else ps.setNull(3, java.sql.Types.INTEGER)
                ps.setString(4, id)
                ps.executeUpdate()
            }
        }
    }

    /** 直接删除 WorkflowRun 记录（模拟手动清理或数据迁移导致的孤立场景） */
    private fun deleteWorkflowRun(id: String) {
        db.useConnection { conn ->
            conn.prepareStatement("DELETE FROM workflow_run WHERE id=?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
        }
    }
}
