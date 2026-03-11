package com.github.project_fredica.db.weben

// =============================================================================
// WebenSourceReconcileTest —— 知识来源状态对账的单元测试
// =============================================================================
//
// ┌──────────────────────────────────────────────────────────────────────────┐
// │                       可靠性保证总览                                       │
// │                                                                          │
// │  问题根源：APP 可以在任意时刻被强杀。此时：                                 │
// │    - Task 可能卡在 running / claimed 状态（没有 Worker 再去完成它）          │
// │    - WorkflowRun 可能卡在 running 状态（recalculate 未被触发）              │
// │    - WebenSource.analysis_status 可能卡在 pending / analyzing             │
// │    - 前端永远显示"排队等待中"，用户无法感知失败，无法重试                   │
// │                                                                          │
// │  解决方案：三段式防御链（缺少任何一段都会有漏洞）                            │
// │                                                                          │
// │  段 1 ── FetchSubtitleExecutor / WebenConceptExtractExecutor              │
// │          executor 在自己的错误返回路径上主动调用                            │
// │          WebenSourceService.repo.updateAnalysisStatus("failed")          │
// │          覆盖：正常的任务失败路径（网络错误、LLM 失败等）                   │
// │          不覆盖：APP 强杀（executor 没有机会运行 finally 块）               │
// │                                                                          │
// │  段 2 ── WorkerEngine.start() → resetStaleTasks() + recalculate()        │
// │          APP 重启时，把残留的 running/claimed 任务打扫干净，               │
// │          让 WorkflowRun.status 正确变成 failed。                          │
// │          覆盖：APP 强杀后的启动恢复                                        │
// │          不覆盖：resetStaleTasks() 自身失败、启动恢复与轮询的竞态          │
// │                                                                          │
// │  段 3 ── WebenSourceListRoute.reconcileSources()                         │
// │          每次前端轮询时执行双层对账：                                       │
// │            第一层：WorkflowRun.status（信任已更新的终态）                  │
// │            第二层：Task 实际状态（WorkflowRun 未同步时的兜底）              │
// │          覆盖：段 2 未完成 / WorkflowRun 落后 / 数据残缺 / 孤立关联        │
// │                                                                          │
// │  保守性原则：                                                              │
// │    - 宁可"晚标记 failed"，也不要"误标记正在运行的任务"                    │
// │    - 只有确认终态（failed / completed）才修改 WebenSource                  │
// │    - 任务仍在 running/pending → 不修改（等下一次轮询 + 恢复完成后再判断）  │
// └──────────────────────────────────────────────────────────────────────────┘
//
// 测试分组：
//   A. resetStaleTasks()              — 段 2 的单元行为验证
//   B. reconcile 由 WorkflowRun 驱动  — 段 3 第一层的行为验证
//   C. reconcile 由 Task 状态驱动     — 段 3 第二层的行为验证（核心兜底）
//   D. 容错性（空指针 / 孤立关联）    — 数据残缺下不崩溃且正确处理
//   E. 端到端集成                     — 三段式防御链的完整验证
//
// 日志格式约定（测试内 println）：
//   [ARRANGE]  建立测试前置状态
//   [BEFORE]   被测操作执行前的状态快照
//   [ACT]      执行被测操作
//   [AFTER]    被测操作执行后的状态快照
//   [ASSERT]   断言检查点（配合 assertEquals 使用）
//   [GUARANTEE] 该测试所验证的可靠性保证描述
// =============================================================================

import com.github.project_fredica.api.routes.WebenSourceListRoute
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskDb
import com.github.project_fredica.db.TaskService
import com.github.project_fredica.db.WorkflowRun
import com.github.project_fredica.db.WorkflowRunDb
import com.github.project_fredica.db.WorkflowRunService
import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebenSourceReconcileTest {

    // ── 测试基础设施 ──────────────────────────────────────────────────────────

    private lateinit var db:            Database
    private lateinit var sourceDb:      WebenSourceDb
    private lateinit var taskDb:        TaskDb
    private lateinit var workflowRunDb: WorkflowRunDb

    /**
     * 每个测试前重建独立的临时文件数据库。
     *
     * 为什么必须用临时文件而非 :memory: ？
     * ktorm 连接池每次 useConnection { } 都会从池中取一个新的 JDBC 连接。
     * SQLite 内存数据库（:memory:）对每个连接都是独立的存储，
     * 导致"A 连接写入的数据，B 连接读不到"，测试完全无法正常运行。
     * 临时文件数据库所有连接共享同一个物理文件，行为与生产环境一致。
     */
    @BeforeTest
    fun setup() = runBlocking {
        val tmpFile = File.createTempFile("weben_reconcile_test_", ".db").also { it.deleteOnExit() }
        db            = Database.connect("jdbc:sqlite:${tmpFile.absolutePath}", "org.sqlite.JDBC")
        sourceDb      = WebenSourceDb(db)
        taskDb        = TaskDb(db)
        workflowRunDb = WorkflowRunDb(db)
        // 建表：WebenSourceDb.initialize() 内含 workflow_run_id 字段的 ALTER TABLE migration
        sourceDb.initialize()
        taskDb.initialize()
        workflowRunDb.initialize()
        // 注册到全局 Service 单例（每次 @BeforeTest 重新注册，保证测试间数据完全隔离）
        WebenSourceService.initialize(sourceDb)
        TaskService.initialize(taskDb)
        WorkflowRunService.initialize(workflowRunDb)
    }

    // ── 测试辅助函数 ─────────────────────────────────────────────────────────

    private fun id()     = UUID.randomUUID().toString()
    private fun nowSec() = System.currentTimeMillis() / 1000L

    /** 创建一个 WebenSource，默认 pending，可选关联 workflowRunId。 */
    private suspend fun makeSource(
        id:             String  = id(),
        analysisStatus: String  = "pending",
        workflowRunId:  String? = null,
        materialId:     String? = null,
    ): WebenSource {
        val source = WebenSource(
            id             = id,
            materialId     = materialId,
            url            = "https://www.bilibili.com/video/BV1test",
            title          = "测试视频",
            sourceType     = "bilibili_video",
            analysisStatus = analysisStatus,
            workflowRunId  = workflowRunId,
            createdAt      = nowSec(),
        )
        sourceDb.create(source)
        return source
    }

    /** 创建一个 WorkflowRun，默认 pending。 */
    private suspend fun makeWorkflowRun(
        id:     String = id(),
        status: String = "pending",
    ): WorkflowRun {
        val wf = WorkflowRun(
            id         = id,
            materialId = "",
            template   = "weben_analyze",
            status     = status,
            totalTasks = 2,
            doneTasks  = 0,
            createdAt  = nowSec(),
        )
        workflowRunDb.create(wf)
        return wf
    }

    /**
     * 创建一个 Task，可精确控制 status / claimedAt / startedAt。
     *
     * status 可以是任意字符串，用于直接模拟数据库中的各种残留状态，
     * 包括 APP 强杀后数据库里会出现的 "running" / "claimed" 状态。
     */
    private suspend fun makeTask(
        id:            String  = id(),
        workflowRunId: String,
        type:          String  = "FETCH_SUBTITLE",
        status:        String  = "pending",
        claimedAt:     Long?   = null,
        startedAt:     Long?   = null,
    ): Task {
        val task = Task(
            id            = id,
            type          = type,
            workflowRunId = workflowRunId,
            materialId    = "",
            status        = status,
            payload       = "{}",
            createdAt     = nowSec(),
            claimedAt     = claimedAt,
            startedAt     = startedAt,
        )
        taskDb.create(task)
        return task
    }

    // =========================================================================
    // 分组 A：resetStaleTasks() —— 段 2 启动恢复的单元验证
    //
    // ┌─────────────────────────────────────────────────────────────────────┐
    // │ 可靠性保证：                                                         │
    // │   APP 重启后的第一件事是清除上次会话的"僵尸任务"。                   │
    // │   没有这个机制，running/claimed/pending 任务永远无法被正确处理，    │
    // │   WorkflowRun 永远停留在 running，WebenSource 永远停留在 pending，  │
    // │   用户界面永久卡死在"排队等待中"。                                  │
    // │                                                                     │
    // │   当前设计（统一取消语义）：                                         │
    // │     running  → cancelled （强杀中断，结果不确定；含 error 字段）    │
    // │     claimed  → cancelled （仅预占未执行，强杀前已被占用）           │
    // │     pending  → cancelled （DAG 上游已取消，依赖链永久阻塞）         │
    // │   统一为 cancelled 让调用方通过 hasCancelled 分支快速路由处理。     │
    // └─────────────────────────────────────────────────────────────────────┘
    // =========================================================================

    /**
     * A1：running 任务 → cancelled
     *
     * 可靠性保证：
     *   "正在执行"意味着 Executor 已经开始消费资源（调用 LLM、写文件等），
     *   APP 强杀后这些操作的最终结果未知。统一标记为 cancelled 让调用方
     *   通过 hasCancelled 分支快速路由，用户可以看到取消状态并选择重新触发。
     *
     *   若不执行此步骤：Task 永远停留在 running → WorkerEngine 的 claimNext()
     *   不会认领已在 running 的任务 → 工作流永远无法推进 → WebenSource 卡死。
     */
    @Test
    fun `A1 resetStaleTasks converts running task to cancelled`() = runBlocking {
        println("\n[GUARANTEE] APP 强杀后 running 任务必须被置为 cancelled，否则工作流永远无法推进")

        // ── Arrange ────────────────────────────────────────────────────────
        val wfId = id()
        makeWorkflowRun(id = wfId, status = "running")
        val task = makeTask(workflowRunId = wfId, status = "running", startedAt = nowSec())
        println("[ARRANGE] 模拟强杀前状态: Task(${task.id.take(8)}) status=running")

        // ── Act ────────────────────────────────────────────────────────────
        println("[ACT] 执行 resetStaleTasks()（模拟 APP 重启后 WorkerEngine 启动恢复）")
        val affectedWfIds = TaskService.repo.resetStaleTasks()
        println("[AFTER] resetStaleTasks 返回受影响 workflowRunIds: $affectedWfIds")

        // ── Assert ─────────────────────────────────────────────────────────
        val updated = TaskService.repo.findById(task.id)!!
        println("[ASSERT] Task(${task.id.take(8)}) 新状态: ${updated.status}（期望: cancelled）")
        assertEquals("cancelled", updated.status,
            "running 任务在 APP 强杀后统一标记为 cancelled（取消语义，含 error 字段记录原因）")

        println("[ASSERT] affectedWfIds 包含 wfId: ${wfId in affectedWfIds}")
        assertTrue(wfId in affectedWfIds,
            "应返回 running 任务所属的 workflowRunId，调用方据此触发 recalculate()")

        Unit
    }

    /**
     * A2：claimed 任务 → cancelled
     *
     * 可靠性保证：
     *   "已认领但未开始"意味着 Executor 还没有做任何事情，
     *   但 APP 强杀时占用关系已无法解除，统一标记为 cancelled
     *   让 WorkflowRun 可以正确汇总到 hasCancelled 分支。
     *
     *   若不执行此步骤：Task 的 status=claimed 让 claimNext() 的
     *   "status='pending' AND..." 条件无法匹配到它，任务被永久遗忘。
     */
    @Test
    fun `A2 resetStaleTasks converts claimed task to cancelled`() = runBlocking {
        println("\n[GUARANTEE] APP 强杀后 claimed 任务统一置为 cancelled（未执行，但占用已失效）")

        val wfId = id()
        makeWorkflowRun(id = wfId, status = "running")
        val task = makeTask(workflowRunId = wfId, status = "claimed", claimedAt = nowSec())
        println("[ARRANGE] 模拟强杀前状态: Task(${task.id.take(8)}) status=claimed（已认领未执行）")

        println("[ACT] 执行 resetStaleTasks()")
        val affectedWfIds = TaskService.repo.resetStaleTasks()

        val updated = TaskService.repo.findById(task.id)!!
        println("[AFTER] Task(${task.id.take(8)}) 新状态: ${updated.status}（期望: cancelled）")
        assertEquals("cancelled", updated.status,
            "claimed 任务在 APP 强杀后置为 cancelled，与 running 保持统一的取消语义")
        assertTrue(wfId in affectedWfIds)
        println("[ASSERT] 通过 — claimed 任务已被标记为 cancelled")

        Unit
    }

    /**
     * A3：running + claimed 混合 —— workflowRunId 不重复返回
     *
     * 可靠性保证：
     *   同一工作流中两种僵尸任务同时存在是最常见的强杀场景
     *   （Task1 正在执行时 Task2 可能刚被认领）。
     *   返回的 workflowRunId 集合用于触发 recalculate()，必须去重，
     *   否则同一 WorkflowRun 会被重复计算（幂等但浪费）。
     */
    @Test
    fun `A3 resetStaleTasks handles mixed running and claimed tasks`() = runBlocking {
        println("\n[GUARANTEE] 同一工作流中 running + claimed 并存时，统一置为 cancelled，workflowRunId 不重复返回")

        val wfId = id()
        makeWorkflowRun(id = wfId, status = "running")
        val taskRunning = makeTask(workflowRunId = wfId, type = "FETCH_SUBTITLE",        status = "running")
        val taskClaimed = makeTask(workflowRunId = wfId, type = "WEBEN_CONCEPT_EXTRACT", status = "claimed")
        println("[ARRANGE] 工作流 ${wfId.take(8)} 含两个僵尸任务:")
        println("          FETCH_SUBTITLE(${taskRunning.id.take(8)})        = running")
        println("          WEBEN_CONCEPT_EXTRACT(${taskClaimed.id.take(8)}) = claimed")

        val affectedWfIds = TaskService.repo.resetStaleTasks()
        println("[AFTER] affectedWfIds = $affectedWfIds（期望只有一个 wfId）")

        assertEquals("cancelled", TaskService.repo.findById(taskRunning.id)!!.status,
            "running → cancelled（强杀中断，取消语义）")
        assertEquals("cancelled", TaskService.repo.findById(taskClaimed.id)!!.status,
            "claimed → cancelled（占用失效，取消语义）")
        assertEquals(setOf(wfId), affectedWfIds,
            "同一工作流的两个任务，workflowRunId 在返回集合中只应出现一次")
        println("[ASSERT] 通过 — running→cancelled, claimed→cancelled, wfId 不重复")

        Unit
    }

    /**
     * A4：没有僵尸任务 → 返回空集合，不修改任何任务
     *
     * 可靠性保证（负向保证）：
     *   正常关闭的会话不应触发任何恢复操作。
     *   这确保 APP 正常重启时不会错误修改已完成的历史分析记录。
     */
    @Test
    fun `A4 resetStaleTasks returns empty set when no stale tasks exist`() = runBlocking {
        println("\n[GUARANTEE] 正常关闭后重启，不存在僵尸任务时，resetStaleTasks 安静地返回空集合")

        val wfId = id()
        makeWorkflowRun(id = wfId, status = "completed")
        makeTask(workflowRunId = wfId, status = "completed")
        makeTask(workflowRunId = wfId, status = "failed")
        makeTask(workflowRunId = wfId, status = "cancelled")
        println("[ARRANGE] 所有任务均为终态（completed / failed / cancelled）")

        val affectedWfIds = TaskService.repo.resetStaleTasks()
        println("[AFTER] affectedWfIds = $affectedWfIds（期望: 空集合）")

        assertTrue(affectedWfIds.isEmpty(),
            "无僵尸任务时应返回空集合，不对任何终态任务产生副作用")
        println("[ASSERT] 通过 — 终态任务未被修改")

        Unit
    }

    /**
     * A5：pending 任务同样被取消
     *
     * 可靠性保证：
     *   pending 任务虽然还没被 Worker 认领，但 APP 重启时如果其上游任务
     *   也已被取消（running/claimed → cancelled），整条 DAG 依赖链永久阻断。
     *   统一将 pending 也取消，确保 WorkflowRun 可以通过 hasCancelled 分支
     *   正确汇总，而不是陷入"有 pending 子任务但上游已终止"的僵死状态。
     */
    @Test
    fun `A5 resetStaleTasks also cancels pending tasks`() = runBlocking {
        println("\n[GUARANTEE] pending 任务在 APP 强杀后一并取消（DAG 上游已断，依赖链不可恢复）")

        val wfId = id()
        makeWorkflowRun(id = wfId, status = "pending")
        val task = makeTask(workflowRunId = wfId, status = "pending")
        println("[ARRANGE] Task(${task.id.take(8)}) status=pending（等待执行）")

        val affectedWfIds = TaskService.repo.resetStaleTasks()
        println("[AFTER] affectedWfIds = $affectedWfIds（期望包含 wfId）")

        assertFalse(affectedWfIds.isEmpty(),
            "pending 任务也被取消，应出现在受影响 workflowRunId 集合中")
        assertEquals("cancelled", TaskService.repo.findById(task.id)!!.status,
            "pending 任务在 APP 重启时一并取消，避免 DAG 僵死")
        println("[ASSERT] 通过 — pending 任务已置为 cancelled")

        Unit
    }

    // =========================================================================
    // 分组 B：reconcile 由 WorkflowRun 状态驱动（段 3 第一层验证）
    //
    // ┌─────────────────────────────────────────────────────────────────────┐
    // │ 可靠性保证：                                                         │
    // │   WorkflowRun.status 是由 recalculate() 从 Task 聚合计算出的，      │
    // │   是所有子任务状态的"已知最终真相"。当它已经是终态时，              │
    // │   WebenSource 应该立即同步，无需再查询 Task 表（快速路径）。         │
    // │                                                                     │
    // │   覆盖的漏洞：                                                       │
    // │     - executor 在写 WebenSource 之前崩溃（段 1 失效）               │
    // │     - 段 2 已完成恢复，WorkflowRun 已是终态，但 WebenSource 未同步  │
    // └─────────────────────────────────────────────────────────────────────┘
    // =========================================================================

    /**
     * B1：WebenSource pending + WorkflowRun failed → 对账为 failed
     *
     * 可靠性保证：
     *   这是"段 2 恢复后前端第一次轮询"的典型场景。
     *   resetStaleTasks() + recalculate() 已经把 WorkflowRun 更新为 failed，
     *   reconcile 在第一层就能检测到并修正 WebenSource。
     *   用户在下一次轮询（最多 5 秒后）就能看到失败状态，而非"排队等待中"。
     */
    @Test
    fun `B1 reconcile syncs pending source when WorkflowRun is failed`() = runBlocking {
        println("\n[GUARANTEE] WorkflowRun=failed 时，无论 WebenSource 是否已更新，对账都能修正它")

        val wfId = id()
        makeWorkflowRun(id = wfId, status = "failed")
        makeTask(workflowRunId = wfId, status = "failed")
        val source = makeSource(analysisStatus = "pending", workflowRunId = wfId)
        println("[ARRANGE] WebenSource(${source.id.take(8)}) status=pending（未同步）")
        println("[ARRANGE] WorkflowRun(${wfId.take(8)}) status=failed（已由 recalculate 更新）")

        println("[ACT] 执行 reconcileSources()")
        WebenSourceListRoute.reconcileSources(listOf(source))

        val updated = WebenSourceService.repo.getById(source.id)!!
        println("[AFTER] WebenSource(${source.id.take(8)}) 新状态: ${updated.analysisStatus}（期望: failed）")
        assertEquals("failed", updated.analysisStatus,
            "WorkflowRun=failed 时，reconcile 第一层直接信任 WorkflowRun，修正 WebenSource")
        println("[ASSERT] 通过 — 前端下次轮询将看到 failed 状态，而非永久的\"排队等待中\"")

        Unit
    }

    /**
     * B2：WebenSource analyzing + WorkflowRun completed → 对账为 completed
     *
     * 可靠性保证：
     *   边缘案例——分析全部完成，但 WebenConceptExtractExecutor 在最后一行
     *   updateAnalysisStatus("completed") 之前崩溃，WebenSource 卡在 analyzing。
     *   reconcile 能兜底修正，确保 UI 最终显示完成而非永久分析中。
     */
    @Test
    fun `B2 reconcile syncs analyzing source when WorkflowRun is completed`() = runBlocking {
        println("\n[GUARANTEE] Executor 在最后一步崩溃导致 WebenSource 卡在 analyzing 时，reconcile 能修正为 completed")

        val wfId = id()
        makeWorkflowRun(id = wfId, status = "completed")
        makeTask(workflowRunId = wfId, status = "completed")
        makeTask(workflowRunId = wfId, status = "completed")
        val source = makeSource(analysisStatus = "analyzing", workflowRunId = wfId)
        println("[ARRANGE] WebenSource(${source.id.take(8)}) status=analyzing（Executor 未能写完 completed）")
        println("[ARRANGE] WorkflowRun(${wfId.take(8)}) status=completed, 两个 Task 均 completed")

        WebenSourceListRoute.reconcileSources(listOf(source))

        val updated = WebenSourceService.repo.getById(source.id)!!
        println("[AFTER] WebenSource 新状态: ${updated.analysisStatus}（期望: completed）")
        assertEquals("completed", updated.analysisStatus,
            "WorkflowRun=completed 时兜底修正 WebenSource，防止 UI 永久显示分析中")
        println("[ASSERT] 通过 — 知识提取完成，用户可以浏览概念图谱")

        Unit
    }

    /**
     * B3：WorkflowRun cancelled → WebenSource failed
     *
     * 可靠性保证：
     *   用户主动取消任务后，WorkflowRun 被标记为 cancelled。
     *   WebenSource 没有 "cancelled" 状态，cancelled 对用户的含义
     *   就是"分析没有完成" = failed。此映射确保 UI 行为语义一致。
     */
    @Test
    fun `B3 reconcile treats cancelled WorkflowRun as failed`() = runBlocking {
        println("\n[GUARANTEE] 用户取消任务后，WebenSource 应显示为 failed（而非永久 pending）")

        val wfId = id()
        makeWorkflowRun(id = wfId, status = "cancelled")
        val source = makeSource(analysisStatus = "pending", workflowRunId = wfId)
        println("[ARRANGE] WorkflowRun(${wfId.take(8)}) status=cancelled（用户主动取消）")
        println("[ARRANGE] WebenSource(${source.id.take(8)}) status=pending（未同步）")

        WebenSourceListRoute.reconcileSources(listOf(source))

        val updated = WebenSourceService.repo.getById(source.id)!!
        println("[AFTER] WebenSource 新状态: ${updated.analysisStatus}（期望: failed）")
        assertEquals("failed", updated.analysisStatus,
            "cancelled 对 WebenSource 的语义是「分析未完成」，映射为 failed 让用户可以重试")
        println("[ASSERT] 通过 — 用户可以看到失败状态并重新发起分析")

        Unit
    }

    /**
     * B4：已是终态的 WebenSource 不受对账影响（终态幂等性）
     *
     * 可靠性保证（负向保证）：
     *   终态（completed / failed）应该是永久的。每次前端轮询都会触发 reconcile，
     *   如果终态来源会被重新修改，已完成的分析结果可能被意外覆盖。
     *   这个测试确保对账算法是"只往终态走，不从终态回退"的单向变换。
     */
    @Test
    fun `B4 reconcile skips sources already in terminal state`() = runBlocking {
        println("\n[GUARANTEE] 终态（completed/failed）是永久的，多次 reconcile 不应覆盖它（幂等性）")

        val wfId1 = id()
        val wfId2 = id()
        makeWorkflowRun(id = wfId1, status = "failed")
        makeWorkflowRun(id = wfId2, status = "failed")
        val completed = makeSource(analysisStatus = "completed", workflowRunId = wfId1)
        val failed    = makeSource(analysisStatus = "failed",    workflowRunId = wfId2)
        println("[ARRANGE] source1(${completed.id.take(8)}) = completed，source2(${failed.id.take(8)}) = failed")
        println("[ARRANGE] 即使 WorkflowRun 状态与 WebenSource 不一致，终态来源也不应被修改")

        val changed = WebenSourceListRoute.reconcileSources(listOf(completed, failed))
        println("[AFTER] changed = $changed（期望: false）")

        assertFalse(changed, "终态来源不应触发任何修改（对账算法是幂等的单向终态变换）")
        assertEquals("completed", WebenSourceService.repo.getById(completed.id)!!.analysisStatus,
            "completed 状态不应被覆盖")
        assertEquals("failed",    WebenSourceService.repo.getById(failed.id)!!.analysisStatus,
            "failed 状态不应被覆盖")
        println("[ASSERT] 通过 — 对账算法满足终态幂等性，多次轮询不产生副作用")

        Unit
    }

    // =========================================================================
    // 分组 C：reconcile 由 Task 实际状态驱动（段 3 第二层 —— 核心兜底）
    //
    // ┌─────────────────────────────────────────────────────────────────────┐
    // │ 可靠性保证：                                                         │
    // │   WorkflowRun.status 由 recalculate() 维护，但 recalculate 可能：  │
    // │     - 还没运行（启动恢复与轮询的竞态）                              │
    // │     - 自身失败（DB 错误）                                          │
    // │     - 根本未被调用（WorkerEngine 协程崩溃）                        │
    // │   第二层直接读 Task 表，绕过 WorkflowRun，给出更可靠的实时状态。   │
    // │                                                                     │
    // │   这是整个对账机制中覆盖范围最广的一层，是真正的"保险丝"。         │
    // └─────────────────────────────────────────────────────────────────────┘
    // =========================================================================

    /**
     * C1：WorkflowRun=running（未同步），Task 全 completed → 推导出 completed
     *
     * 可靠性保证：
     *   这是 recalculate() 失败或未调用时的典型漏洞。
     *   如果只检查 WorkflowRun（第一层），会认为"仍在运行中"，不修改 WebenSource。
     *   第二层直接读 Task，发现全部完成，推导出 completed 并补偿触发 recalculate。
     *
     *   "如果没有第二层会怎样"：
     *     WebenSource 永久停留在 analyzing，用户界面一直显示"分析中"，
     *     即使概念图谱已经完整写入数据库。
     */
    @Test
    fun `C1 reconcile derives completed from tasks when WorkflowRun is stale running`() = runBlocking {
        println("\n[GUARANTEE] WorkflowRun 落后时，第二层从 Task 直接推导终态，防止 WebenSource 永久卡在 analyzing")
        println("[SCENARIO] recalculate() 未被调用 → WorkflowRun.status 仍是 running，但任务全部完成")

        val wfId = id()
        // WorkflowRun 故意保持 running（模拟 recalculate 未被调用）
        makeWorkflowRun(id = wfId, status = "running")
        makeTask(workflowRunId = wfId, status = "completed")
        makeTask(workflowRunId = wfId, status = "completed")
        val source = makeSource(analysisStatus = "analyzing", workflowRunId = wfId)
        println("[ARRANGE] WorkflowRun status=running（未同步）, 两个 Task 均 completed")
        println("[ARRANGE] WebenSource status=analyzing（Executor 已完成但 WorkflowRun 未更新）")

        println("[ACT] reconcileSources() — 期望第二层 Task 检查推导出 completed")
        WebenSourceListRoute.reconcileSources(listOf(source))

        val updated = WebenSourceService.repo.getById(source.id)!!
        println("[AFTER] WebenSource 新状态: ${updated.analysisStatus}（期望: completed）")
        assertEquals("completed", updated.analysisStatus,
            "第二层：Task 全 completed 时，应推导出 completed，不被落后的 WorkflowRun 误导")

        // 重要副作用：reconcile 还应补偿触发 recalculate，修正 WorkflowRun
        val wf = WorkflowRunService.repo.getById(wfId)!!
        println("[AFTER] WorkflowRun 补偿修正后状态: ${wf.status}（期望: completed）")
        assertEquals("completed", wf.status,
            "对账发现 WorkflowRun 落后时，应补偿触发 recalculate() 使其重新同步")
        println("[ASSERT] 通过 — 第二层兜底成功，WebenSource 和 WorkflowRun 均已正确同步")

        Unit
    }

    /**
     * C2：WorkflowRun=running（未同步），Task 中有 failed → 推导出 failed
     *
     * 可靠性保证：
     *   这是"段 2 已运行（Task 已变为 failed），但 recalculate 因竞态尚未执行"的场景。
     *   时序：resetStaleTasks() 把 running→failed，但对账轮询在 recalculate()
     *   之前就到来了——WorkflowRun 仍是 running，但 Task 已经是 failed。
     *   第二层检测到 Task.failed，正确推导出 failed，不需要等 recalculate。
     *
     *   "如果没有第二层会怎样"：
     *     这次轮询无法更新 WebenSource（WorkflowRun 仍显示 running），
     *     需要等到 recalculate 执行并触发下一次轮询，才能看到失败状态。
     *     用户体验：失败恢复时间延长，最差情况下延迟数秒。
     *     （有了第二层：当次轮询即可完成修正）
     */
    @Test
    fun `C2 reconcile derives failed from tasks when WorkflowRun is stale running`() = runBlocking {
        println("\n[GUARANTEE] resetStaleTasks 后 recalculate 尚未执行时，第二层从 Task 立即推导出 failed")
        println("[SCENARIO] Task 已被 resetStaleTasks 置为 failed，但 recalculate 还在队列中等待")

        val wfId = id()
        // WorkflowRun 仍是 running（recalculate 还没运行）
        makeWorkflowRun(id = wfId, status = "running")
        // Task1 已被 resetStaleTasks 置为 failed
        makeTask(workflowRunId = wfId, type = "FETCH_SUBTITLE",        status = "failed")
        // Task2 因依赖未满足永远停留 pending
        makeTask(workflowRunId = wfId, type = "WEBEN_CONCEPT_EXTRACT", status = "pending")
        val source = makeSource(analysisStatus = "pending", workflowRunId = wfId)
        println("[ARRANGE] FETCH_SUBTITLE(failed) + WEBEN_CONCEPT_EXTRACT(pending，依赖永远不会满足)")
        println("[ARRANGE] WorkflowRun 仍是 running（recalculate 未执行）")

        println("[ACT] reconcileSources() — 期望第二层发现 Task.failed 推导出 failed")
        WebenSourceListRoute.reconcileSources(listOf(source))

        val updated = WebenSourceService.repo.getById(source.id)!!
        println("[AFTER] WebenSource 新状态: ${updated.analysisStatus}（期望: failed）")
        assertEquals("failed", updated.analysisStatus,
            "第二层：发现 Task 中有 failed，立即推导出 failed，不等 recalculate 完成")
        println("[ASSERT] 通过 — 当次轮询即修正完成，无需等待额外的轮询周期")

        Unit
    }

    /**
     * C3：WorkflowRun=running，Task cancelled → 推导出 failed
     *
     * 可靠性保证：
     *   用户取消 Task1 后，Task2（依赖 Task1）永远无法执行。
     *   WorkflowRun 的 recalculate 对于 "部分 cancelled" 状态有其自己的逻辑，
     *   但 WebenSource 不应等待 WorkflowRun 更新——cancelled 任务对分析而言
     *   就等于失败，应立即标记 failed 让用户可以选择重试。
     */
    @Test
    fun `C3 reconcile treats cancelled task as failure`() = runBlocking {
        println("\n[GUARANTEE] 用户取消任意任务后，分析链断裂，WebenSource 应立即对账为 failed")

        val wfId = id()
        makeWorkflowRun(id = wfId, status = "running")
        makeTask(workflowRunId = wfId, status = "cancelled")
        val source = makeSource(analysisStatus = "pending", workflowRunId = wfId)
        println("[ARRANGE] Task cancelled（用户主动取消），WorkflowRun 仍是 running")

        WebenSourceListRoute.reconcileSources(listOf(source))

        val status = WebenSourceService.repo.getById(source.id)!!.analysisStatus
        println("[AFTER] WebenSource 新状态: $status（期望: failed）")
        assertEquals("failed", status,
            "cancelled 任务使分析链断裂，WebenSource 应对账为 failed")
        println("[ASSERT] 通过")

        Unit
    }

    /**
     * C4：WorkflowRun=running，Task 仍在 running/pending → 不修改（保守性）
     *
     * 可靠性保证（负向保证）：
     *   这是对"保守性原则"最重要的验证——分析正在正常推进时，
     *   任何一层对账都不应产生任何修改。
     *
     *   如果对账算法不够保守（例如"running 超过 N 分钟就认为是僵尸"），
     *   就可能把正常进行的 ASR 转录（可能持续数十分钟）误标记为失败。
     *   这是比"晚看到失败"更严重的错误——分析还在正常运行，用户却看到失败。
     */
    @Test
    fun `C4 reconcile does not modify source when tasks are genuinely in progress`() = runBlocking {
        println("\n[GUARANTEE] 分析正常推进时，对账绝对不会误标记为 failed（保守性是最高优先级）")
        println("[PRINCIPLE] 宁可\"晚发现失败\"，也不\"误报失败破坏正在运行的分析\"")

        val wfId = id()
        makeWorkflowRun(id = wfId, status = "running")
        makeTask(workflowRunId = wfId, status = "running")  // Task1 正在执行（合法状态）
        makeTask(workflowRunId = wfId, status = "pending")  // Task2 等待 Task1 完成
        val source = makeSource(analysisStatus = "analyzing", workflowRunId = wfId)
        println("[ARRANGE] FETCH_SUBTITLE(running) + WEBEN_CONCEPT_EXTRACT(pending) → 分析正在推进")

        val changed = WebenSourceListRoute.reconcileSources(listOf(source))
        println("[AFTER] changed = $changed（期望: false，分析中不应被修改）")

        assertFalse(changed, "任务正常推进中，对账算法不应产生任何修改")
        assertEquals("analyzing", WebenSourceService.repo.getById(source.id)!!.analysisStatus,
            "分析中的状态不应被任何层级的对账覆盖")
        println("[ASSERT] 通过 — 保守性验证通过，正常分析不受对账干扰")

        Unit
    }

    // =========================================================================
    // 分组 D：容错性 —— 空指针、孤立关联、数据残缺下不崩溃
    //
    // ┌─────────────────────────────────────────────────────────────────────┐
    // │ 可靠性保证：                                                         │
    // │   数据库中可能存在各种"不完整"状态：                                │
    // │     - 迁移前创建的旧记录（无 workflow_run_id 字段）                 │
    // │     - WorkflowRun 被手动删除（孤立关联）                           │
    // │     - Task 被手动删除（孤立工作流）                                │
    // │     - 多种混合状态并存                                              │
    // │   对账必须在所有这些情况下安全运行：不抛异常，不崩溃，              │
    // │   并对每种残缺状态给出合理的处理结果。                              │
    // │                                                                     │
    // │   设计原则：                                                         │
    // │     - workflowRunId 为 null（无工作流）→ 标记 failed               │
    // │       （没有 WorkflowRun 可以推进分析，永久卡死比显示失败更糟糕）   │
    // │     - 确认已损坏的（WF 不存在 / Task 全被删除）→ 标记 failed       │
    // │       （已损坏的分析无法恢复，失败比永久 pending 更合理）           │
    // └─────────────────────────────────────────────────────────────────────┘
    // =========================================================================

    /**
     * D1：workflow_run_id 为 null —— 标记 failed（避免永久卡死）
     *
     * 可靠性保证：
     *   workflow_run_id 为 null 且处于非终态，意味着分析流水线从未被正确关联。
     *   可能原因：
     *   - 写入 WebenSource 后创建 WorkflowRun 时发生异常（事务之间的窗口期）
     *   - 旧版本数据迁移前创建的记录，彼时 workflow_run_id 字段尚不存在
     *
     *   关键问题：没有 WorkflowRun 驱动分析，此来源将永远停留在非终态——
     *   前端永远显示"排队中"，用户无法感知也无法重试（比显示失败更糟糕）。
     *
     *   正确处理：标记为 failed。WebenSource 没有 cancelled 状态，
     *   failed 是此类孤立记录唯一合适的终态，用户可据此选择重新提交。
     */
    @Test
    fun `D1 reconcile marks failed when workflow_run_id is null`() = runBlocking {
        println("\n[GUARANTEE] workflow_run_id=null 的非终态来源，对账标记为 failed，避免永久卡死")
        println("[SCENARIO] 写入中断或旧版本迁移前数据，导致 workflowRunId 未关联")

        val source = makeSource(analysisStatus = "pending", workflowRunId = null)
        println("[ARRANGE] WebenSource(${source.id.take(8)}) status=pending, workflow_run_id=null")

        val changed = WebenSourceListRoute.reconcileSources(listOf(source))
        println("[AFTER] changed = $changed（期望: true），无异常抛出")

        assertTrue(changed,
            "null workflowRunId → 应标记 failed，返回 changed=true")
        assertEquals("failed", WebenSourceService.repo.getById(source.id)!!.analysisStatus,
            "无 WorkflowRun 可推进分析 → 永久卡死，应对账为 failed 让用户可以重新提交")
        println("[ASSERT] 通过 — 旧数据 / 写入中断的来源被标记为 failed，不再永久卡死")

        Unit
    }

    /**
     * D1b：analyzing 状态 + workflow_run_id 为 null —— 同样标记 failed
     *
     * D1 已验证 pending + null wfId，此用例补全 analyzing 状态的对称场景，
     * 确保对账逻辑不因 analysisStatus 的值不同而产生遗漏。
     */
    @Test
    fun `D1b reconcile marks failed when analyzing source has null workflow_run_id`() = runBlocking {
        println("\n[GUARANTEE] analyzing + null wfId，与 pending + null wfId 同样标记 failed")

        val source = makeSource(analysisStatus = "analyzing", workflowRunId = null)
        println("[ARRANGE] WebenSource(${source.id.take(8)}) status=analyzing, workflow_run_id=null")

        val changed = WebenSourceListRoute.reconcileSources(listOf(source))
        println("[AFTER] changed = $changed（期望: true）")

        assertTrue(changed, "analyzing + null wfId 也应标记 failed")
        assertEquals("failed", WebenSourceService.repo.getById(source.id)!!.analysisStatus,
            "analyzing 状态同样无法在 null wfId 下继续推进，应对账为 failed")
        println("[ASSERT] 通过 — analyzing + null wfId 正确标记 failed")

        Unit
    }

    /**
     * D2：workflow_run_id 指向不存在的 WorkflowRun —— 对账为 failed（孤立记录）
     *
     * 可靠性保证：
     *   这是数据损坏场景：WorkflowRun 记录已不存在（可能被手动删除或数据库损坏），
     *   但 WebenSource 仍然持有它的 ID。
     *   对账不应崩溃（getById 返回 null 时需要正确处理），
     *   且应将这类孤立来源标记为 failed——它的分析链已无法恢复，
     *   永久停留在 pending 比显示失败更糟糕（用户无法感知也无法重试）。
     */
    @Test
    fun `D2 reconcile marks failed when WorkflowRun has been deleted (orphan)`() = runBlocking {
        println("\n[GUARANTEE] WorkflowRun 已被删除（孤立关联），对账不崩溃且将来源标记为 failed")
        println("[SCENARIO] 数据库损坏或手动删除导致 WorkflowRun 记录丢失")

        val source = makeSource(analysisStatus = "pending", workflowRunId = "non-existent-wf-${id().take(8)}")
        println("[ARRANGE] WebenSource(${source.id.take(8)}) 指向不存在的 WorkflowRun")
        println("[ARRANGE] WorkflowRunService.getById() 将返回 null")

        // 关键：不应抛 NullPointerException 或 NoSuchElementException
        WebenSourceListRoute.reconcileSources(listOf(source))

        val updated = WebenSourceService.repo.getById(source.id)!!
        println("[AFTER] WebenSource 新状态: ${updated.analysisStatus}（期望: failed）")
        assertEquals("failed", updated.analysisStatus,
            "孤立来源（WorkflowRun 已删除）无法恢复分析，标记 failed 让用户可以重新提交")
        println("[ASSERT] 通过 — 孤立关联被正确处理，无异常，来源显示失败而非永久 pending")

        Unit
    }

    /**
     * D3：WorkflowRun 存在但其所有 Task 被删除（孤立工作流）→ 对账为 failed
     *
     * 可靠性保证：
     *   WorkflowRun 仍存在，但它的所有 Task 子记录被删除。
     *   listByWorkflowRun() 返回空列表，这意味着工作流已无法继续推进。
     *   与 D2 类似，空 Task 列表意味着分析链断裂，标记 failed 是正确的。
     *
     *   如果对 "tasks.isEmpty()" 不做处理而继续走 "all completed → completed" 逻辑，
     *   则空列表满足 "all(condition)" 的真空真值，错误地把 WebenSource 标记为 completed！
     *   （空列表的 all{} 在 Kotlin 中返回 true）
     *   这个测试同时验证了对"空列表真空真值陷阱"的防御。
     */
    @Test
    fun `D3 reconcile marks failed when WorkflowRun has no tasks (all tasks deleted)`() = runBlocking {
        println("\n[GUARANTEE] WorkflowRun 存在但所有 Task 被删除（孤立工作流），对账为 failed 而非 completed")
        println("[TRAP]     Kotlin: emptyList<Task>().all { it.status == \"completed\" } == true（真空真值！）")
        println("[DEFENSE]  对账逻辑必须先检查 tasks.isEmpty()，防止空列表被误判为\"全部完成\"")

        val wfId = id()
        // 创建 WorkflowRun 但不创建任何 Task（模拟 Task 被删除）
        makeWorkflowRun(id = wfId, status = "running")
        val source = makeSource(analysisStatus = "pending", workflowRunId = wfId)
        println("[ARRANGE] WorkflowRun(${wfId.take(8)}) status=running，但无任何 Task 子记录")

        WebenSourceListRoute.reconcileSources(listOf(source))

        val updated = WebenSourceService.repo.getById(source.id)!!
        println("[AFTER] WebenSource 新状态: ${updated.analysisStatus}（期望: failed）")
        assertEquals("failed", updated.analysisStatus,
            "空 Task 列表不等于\"全部完成\"，应被识别为工作流损坏并对账为 failed")
        println("[ASSERT] 通过 — 真空真值陷阱被防御，孤立工作流正确标记失败")

        Unit
    }

    /**
     * D4：多条来源并发对账，每条独立处理，互不干扰
     *
     * 可靠性保证：
     *   前端每次轮询可能传入 N 条来源（同一素材可能有多次分析记录）。
     *   每条来源的对账逻辑必须完全独立——一条来源的异常（如孤立 WorkflowRun）
     *   不能中断其他来源的对账。runCatching 包裹确保了这一隔离性。
     *
     *   此测试用 5 种不同状态验证完整的输入矩阵，确保所有情况被覆盖。
     */
    @Test
    fun `D4 reconcile handles multiple sources with mixed states correctly`() = runBlocking {
        println("\n[GUARANTEE] 多条来源并发对账时，各自独立处理，任意一条异常不影响其他来源")

        val wfId1 = id(); makeWorkflowRun(id = wfId1, status = "failed")
        val wfId2 = id(); makeWorkflowRun(id = wfId2, status = "completed")
        val wfId5 = id(); makeWorkflowRun(id = wfId5, status = "running")

        makeTask(workflowRunId = wfId1, status = "failed")
        makeTask(workflowRunId = wfId2, status = "completed")
        makeTask(workflowRunId = wfId5, status = "running")

        val source1 = makeSource(analysisStatus = "pending",   workflowRunId = wfId1)
        val source2 = makeSource(analysisStatus = "analyzing", workflowRunId = wfId2)
        val source3 = makeSource(analysisStatus = "pending",   workflowRunId = null)         // 写入中断/旧数据
        val source4 = makeSource(analysisStatus = "pending",   workflowRunId = "deleted-wf") // 孤立关联
        val source5 = makeSource(analysisStatus = "analyzing", workflowRunId = wfId5)        // 正常推进

        println("[ARRANGE] 5 条来源:")
        println("  source1: pending  + WF=failed    → 期望 failed")
        println("  source2: analyzing + WF=completed → 期望 completed")
        println("  source3: pending  + null wfId     → 期望 failed（无工作流，永久卡死）")
        println("  source4: pending  + 孤立 wfId     → 期望 failed")
        println("  source5: analyzing + 正常推进     → 期望不变（analyzing）")

        val changed = WebenSourceListRoute.reconcileSources(
            listOf(source1, source2, source3, source4, source5)
        )

        println("[AFTER] 对账结果:")
        val s1 = WebenSourceService.repo.getById(source1.id)!!.analysisStatus
        val s2 = WebenSourceService.repo.getById(source2.id)!!.analysisStatus
        val s3 = WebenSourceService.repo.getById(source3.id)!!.analysisStatus
        val s4 = WebenSourceService.repo.getById(source4.id)!!.analysisStatus
        val s5 = WebenSourceService.repo.getById(source5.id)!!.analysisStatus
        println("  source1: $s1（期望 failed）")
        println("  source2: $s2（期望 completed）")
        println("  source3: $s3（期望 failed，null wfId → 无工作流永久卡死）")
        println("  source4: $s4（期望 failed，孤立）")
        println("  source5: $s5（期望 analyzing，不变）")

        assertTrue(changed, "至少有几条来源被修改")
        assertEquals("failed",    s1, "source1: WF=failed → failed")
        assertEquals("completed", s2, "source2: WF=completed → completed")
        assertEquals("failed",    s3, "source3: null wfId → failed（无工作流，永久卡死）")
        assertEquals("failed",    s4, "source4: 孤立 wfId → failed（孤立视为损坏）")
        assertEquals("analyzing", s5, "source5: 正常推进 → 不修改")
        println("[ASSERT] 通过 — 5 种情况全部正确处理，互不干扰")

        Unit
    }

    // =========================================================================
    // 分组 E：端到端集成场景 —— 验证三段式防御链的完整性
    //
    // ┌─────────────────────────────────────────────────────────────────────┐
    // │ 这些测试验证的是"多个机制协同工作"时的系统级可靠性保证，             │
    // │ 而非单一机制的行为。每个测试覆盖一个真实的操作场景。                │
    // └─────────────────────────────────────────────────────────────────────┘
    // =========================================================================

    /**
     * E1：完整的 APP 强杀与三段式恢复流程
     *
     * 可靠性保证（最重要的端到端验证）：
     *   模拟最常见的强杀场景，验证整个恢复链路的完整性：
     *
     *   强杀前：Task=running, WorkflowRun=running, WebenSource=pending
     *   ↓ APP 重启，WorkerEngine.start() 执行：
     *   resetStaleTasks()   → Task=cancelled（running/pending 统一取消）
     *   recalculate()       → WorkflowRun=cancelled（全部子任务已取消）
     *   ↓ 前端轮询，触发：
     *   reconcileSources()  → WebenSource=failed（cancelled WF → failed source）
     *
     *   没有任何一段可以缺失：
     *     - 缺少 resetStaleTasks：Task 永远是 running → WorkflowRun 无法变 cancelled
     *     - 缺少 recalculate：WorkflowRun 永远是 running → 第一层对账无法工作
     *     - 缺少第二层 Task 检查：若竞态导致 recalculate 未完成，对账也能兜底
     */
    @Test
    fun `E1 full app-kill recovery flow ends with source marked failed`() = runBlocking {
        println("\n════════════════════════════════════════════════════════════════")
        println("[E1] APP 强杀完整恢复流程：三段式防御链端到端验证")
        println("════════════════════════════════════════════════════════════════")

        // ── [BEFORE KILL] 模拟强杀前的数据库状态 ───────────────────────────
        val wfId = id()
        makeWorkflowRun(id = wfId, status = "running")
        val task1 = makeTask(
            workflowRunId = wfId,
            type          = "FETCH_SUBTITLE",
            status        = "running",
            startedAt     = nowSec(),
        )
        makeTask(workflowRunId = wfId, type = "WEBEN_CONCEPT_EXTRACT", status = "pending")
        val source = makeSource(analysisStatus = "pending", workflowRunId = wfId)

        println("[BEFORE KILL] Task(${task1.id.take(8)}) FETCH_SUBTITLE = running（正在执行）")
        println("[BEFORE KILL] WorkflowRun(${wfId.take(8)}) = running")
        println("[BEFORE KILL] WebenSource(${source.id.take(8)}) analysis_status = pending")
        println("[BEFORE KILL] ★ APP 被强杀，所有协程终止，数据库状态冻结在此 ★")

        // 验证强杀前的初始状态
        assertEquals("running", TaskService.repo.findById(task1.id)!!.status)
        assertEquals("pending", WebenSourceService.repo.getById(source.id)!!.analysisStatus)

        // ── [RESTART] 段 2：WorkerEngine.start() 启动恢复 ─────────────────
        println("\n[RESTART] APP 重启，WorkerEngine.start() 开始执行...")
        println("[RESTART] 段 2 — 执行 resetStaleTasks()（running/pending → cancelled）")
        val affectedWfIds = TaskService.repo.resetStaleTasks()
        println("[RESTART] resetStaleTasks 返回受影响 workflowRunIds: $affectedWfIds")

        println("[RESTART] 段 2 — 对受影响工作流执行 recalculate()（重新统计子任务状态）")
        affectedWfIds.forEach { WorkflowRunService.repo.recalculate(it) }

        // 验证段 2 结果
        val taskAfterReset = TaskService.repo.findById(task1.id)!!
        val wfAfterReset   = WorkflowRunService.repo.getById(wfId)!!
        println("[AFTER RESTART] Task(${task1.id.take(8)}) 新状态: ${taskAfterReset.status}（期望: cancelled）")
        println("[AFTER RESTART] WorkflowRun(${wfId.take(8)}) 新状态: ${wfAfterReset.status}（期望: cancelled）")
        assertEquals("cancelled", taskAfterReset.status,
            "段 2：resetStaleTasks 应将 running 任务置为 cancelled")
        assertEquals("cancelled", wfAfterReset.status,
            "段 2：recalculate 应将 WorkflowRun 重新计算为 cancelled（全部子任务均已取消）")

        // ── [POLL] 段 3：前端轮询，reconcileSources ────────────────────────
        println("\n[POLL] 前端发起轮询（最多 5 秒后），WebenSourceListRoute 执行对账...")
        val sources = WebenSourceService.repo.listAll()
        println("[POLL] 段 3 — 执行 reconcileSources()（双层对账）")
        WebenSourceListRoute.reconcileSources(sources)

        // 验证最终结果
        val finalSource = WebenSourceService.repo.getById(source.id)!!
        println("[AFTER RECONCILE] WebenSource(${source.id.take(8)}) 最终状态: ${finalSource.analysisStatus}（期望: failed）")
        assertEquals("failed", finalSource.analysisStatus,
            "三段式防御链：WF=cancelled → reconcile 映射为 failed，前端不再显示\"排队等待中\"")

        println("\n[RESULT] ✓ 强杀恢复流程验证通过")
        println("[RESULT] 用户将在下一次轮询（~5秒内）看到失败状态，可重新发起分析")
        println("════════════════════════════════════════════════════════════════")

        Unit
    }

    /**
     * E2：正常完成流程不被对账误判（非破坏性保证）
     *
     * 可靠性保证：
     *   这是对"保守性原则"的系统级验证——对账机制在正常流程中
     *   应该是完全透明的，不产生任何副作用。
     *   确保任何级别的对账（多次 reconcile 调用）都是幂等的。
     */
    @Test
    fun `E2 successful analysis is not disturbed by reconcile`() = runBlocking {
        println("\n[GUARANTEE] 对账机制对正常完成的分析完全透明，不产生任何副作用（非破坏性）")

        val wfId = id()
        makeWorkflowRun(id = wfId, status = "completed")
        makeTask(workflowRunId = wfId, status = "completed")
        makeTask(workflowRunId = wfId, status = "completed")
        val source = makeSource(analysisStatus = "completed", workflowRunId = wfId)
        println("[ARRANGE] 分析正常完成：WebenSource=completed, WorkflowRun=completed, 所有 Task=completed")

        // 执行多次对账，验证幂等性
        println("[ACT] 执行 reconcileSources() 两次（验证幂等性）")
        val changed1 = WebenSourceListRoute.reconcileSources(listOf(source))
        val changed2 = WebenSourceListRoute.reconcileSources(
            listOf(WebenSourceService.repo.getById(source.id)!!)
        )
        println("[AFTER] 第一次 changed=$changed1，第二次 changed=$changed2（均期望 false）")

        assertFalse(changed1, "第一次对账：已完成的来源不应被修改")
        assertFalse(changed2, "第二次对账：对账是幂等的，再次执行不产生修改")
        assertEquals("completed", WebenSourceService.repo.getById(source.id)!!.analysisStatus,
            "多次对账后，已完成的来源状态仍应保持 completed")
        println("[ASSERT] 通过 — 对账机制幂等，不干扰正常流程")

        Unit
    }

    /**
     * E3：竞态场景 —— resetStaleTasks 未执行，对账保守跳过
     *
     * 可靠性保证：
     *   APP 重启后，WorkerEngine 启动恢复（段 2）和前端首次轮询（段 3）
     *   之间存在竞态。若轮询先到，Task 还是 running，WorkflowRun 还是 running，
     *   对账的第二层会认为"仍在推进中"，保守地不修改 WebenSource。
     *
     *   这是正确行为：
     *     - 宁可这次轮询不更新（等段 2 完成后再更新），
     *     - 也不要把"仍是 running 状态"的 Task 误认为失败。
     *
     *   等段 2 完成后（Task 变为 failed），下一次轮询（~5秒后）才会触发修正。
     *   这个延迟是可接受的，换来的是"不误报正在运行的分析"的可靠性保证。
     */
    @Test
    fun `E3 reconcile is conservative when reset has not run yet`() = runBlocking {
        println("\n[GUARANTEE] resetStaleTasks 未执行时，对账保守跳过，不误报 failed（允许小延迟换取准确性）")
        println("[RACE CONDITION] 场景：前端轮询先于 WorkerEngine 启动恢复到达")

        val wfId = id()
        makeWorkflowRun(id = wfId, status = "running")
        // Task 还是 running（模拟 resetStaleTasks 还未执行，APP 强杀后的初始状态）
        makeTask(workflowRunId = wfId, status = "running")
        makeTask(workflowRunId = wfId, status = "pending")
        val source = makeSource(analysisStatus = "pending", workflowRunId = wfId)
        println("[ARRANGE] 强杀后初始状态：Task=running, WorkflowRun=running（resetStaleTasks 未执行）")
        println("[ARRANGE] 此时前端轮询先到，对账被触发")

        val changed = WebenSourceListRoute.reconcileSources(listOf(source))
        println("[AFTER] changed = $changed（期望: false，等下次轮询）")

        assertFalse(changed,
            "resetStaleTasks 未运行时，running Task 看起来仍在推进中，对账保守跳过")
        assertEquals("pending", WebenSourceService.repo.getById(source.id)!!.analysisStatus,
            "竞态下暂时保持 pending，等待段 2 完成后下一次轮询才修正")
        println("[ASSERT] 通过 — 保守跳过，无误报。约 5 秒后段 2 完成，下次轮询将触发修正")
        println("[TRADEOFF] 允许最多一个轮询周期（~5秒）的延迟，换取\"不误标正在运行的任务\"的可靠性")

        Unit
    }

    /**
     * E4：三段式流水线的数据流正确性验证
     *
     * 可靠性保证：
     *   对每一段的输出作为下一段输入的"接口契约"进行验证：
     *
     *   契约 1：resetStaleTasks() → 返回 workflowRunId 集合
     *             ✓ 集合包含有 running 任务的工作流
     *             ✓ 集合不包含无 running 任务的工作流
     *
     *   契约 2：recalculate(workflowRunId) → WorkflowRun.status 正确变更
     *             ✓ 所有子任务被 resetStaleTasks 取消后 → "cancelled"
     *               （resetStaleTasks 现在将 running/claimed/pending 统一改为 cancelled，
     *                 而非 failed；recalculate 的 hasCancelled 分支会正确返回 "cancelled"）
     *             ✓ 全部 completed 时 → "completed"
     *
     *   契约 3：reconcileSources(sources) → WebenSource.status 与 WorkflowRun 同步
     *             ✓ WorkflowRun=cancelled → WebenSource=failed（cancelled WF 映射到 failed，语义等价）
     *             ✓ WorkflowRun=completed → WebenSource=completed
     *
     *   任意一段的契约被破坏，整个三段式防御链就会失效。
     */
    @Test
    fun `E4 three-stage pipeline data contract is correct at each boundary`() = runBlocking {
        println("\n[GUARANTEE] 三段式防御链各段的数据接口契约验证")

        val wfId = id()
        makeWorkflowRun(id = wfId, status = "running")
        makeTask(workflowRunId = wfId, status = "running")
        val source = makeSource(analysisStatus = "pending", workflowRunId = wfId)

        // ── 契约 1：resetStaleTasks 的输出契约 ────────────────────────────
        println("\n[CONTRACT 1] resetStaleTasks() → Set<workflowRunId>")
        val affected = TaskService.repo.resetStaleTasks()
        println("  输出: $affected")
        assertEquals(setOf(wfId), affected,
            "契约 1：有 running 任务的工作流必须出现在返回集合中")

        // ── 契约 2：recalculate 的输出契约 ────────────────────────────────
        println("\n[CONTRACT 2] recalculate(wfId) → WorkflowRun.status")
        affected.forEach { WorkflowRunService.repo.recalculate(it) }
        val wfStatus = WorkflowRunService.repo.getById(wfId)!!.status
        println("  WorkflowRun(${wfId.take(8)}).status = $wfStatus")
        // resetStaleTasks 将 running → cancelled（而非 failed），
        // recalculate 的 hasCancelled 分支将 WorkflowRun 推导为 "cancelled"
        assertEquals("cancelled", wfStatus,
            "契约 2：resetStaleTasks 后子任务均为 cancelled，recalculate 必须将 WorkflowRun.status 更新为 cancelled")

        // ── 契约 3：reconcileSources 的输出契约 ───────────────────────────
        println("\n[CONTRACT 3] reconcileSources() → WebenSource.analysis_status")
        val sources = WebenSourceService.repo.listAll()
        WebenSourceListRoute.reconcileSources(sources)
        val sourceStatus = WebenSourceService.repo.getById(source.id)!!.analysisStatus
        println("  WebenSource(${source.id.take(8)}).analysis_status = $sourceStatus")
        assertEquals("failed", sourceStatus,
            "契约 3：WorkflowRun=cancelled 时，reconcile 必须将 WebenSource.analysis_status 更新为 failed（cancelled WF 语义等同于 failed）")

        println("\n[RESULT] ✓ 三段式数据接口契约全部满足，防御链完整可靠")

        Unit
    }
}
