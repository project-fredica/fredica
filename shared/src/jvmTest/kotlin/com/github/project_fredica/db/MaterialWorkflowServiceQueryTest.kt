package com.github.project_fredica.db

// =============================================================================
// MaterialWorkflowServiceQueryTest —— MaterialWorkflowService 查询方法集成测试
// =============================================================================
//
// 被测对象：MaterialWorkflowService.queryActive() / queryHistory()
//
// 测试策略：
//   - 每个测试使用独立的 SQLite 临时文件（@BeforeTest 重建），数据完全隔离。
//   - 通过直接向 WorkflowRunDb / TaskDb 插入数据构造场景（不依赖外部进程）。
//   - StartupReconcileGuard 是进程级单例；各测试使用唯一 materialId（含 nanoTime），
//     避免跨测试共享 Guard 状态导致对账被跳过（对查询逻辑本身无影响，此处防御性隔离）。
//
// 测试矩阵：
//   Q1. queryActive - 无活跃工作流时返回空列表
//   Q2. queryActive - 返回 running/pending 工作流及其子任务
//   Q3. queryActive - 不返回终态（completed/failed/cancelled）工作流
//   Q4. queryActive - 多个活跃工作流时每个携带正确子任务
//   Q5. queryHistory - 返回终态工作流（分页），total 正确
//   Q6. queryHistory - 不返回活跃工作流
//   Q7. queryHistory - 分页返回正确子集
// =============================================================================

import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaterialWorkflowServiceQueryTest {

    private lateinit var db: Database
    private lateinit var taskDb: TaskDb
    private lateinit var workflowRunDb: WorkflowRunDb

    // 每个测试实例的唯一前缀，避免 StartupReconcileGuard materialId 命中缓存。
    private val testSeq  = seq.incrementAndGet()
    private val matBase  = "mat-query-$testSeq"

    companion object {
        private val seq = AtomicInteger(0)
    }

    @BeforeTest
    fun setup() = runBlocking {
        val tmpFile = File.createTempFile("wf_query_test_", ".db").also { it.deleteOnExit() }
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

    // ── 辅助方法 ──────────────────────────────────────────────────────────────

    private fun nowSec() = System.currentTimeMillis() / 1000L

    private suspend fun createRun(id: String, materialId: String, status: String, total: Int = 1) {
        workflowRunDb.create(
            WorkflowRun(
                id          = id,
                materialId  = materialId,
                template    = "bilibili_download_transcode",
                status      = status,
                totalTasks  = total,
                doneTasks   = if (status == "completed") total else 0,
                createdAt   = nowSec(),
            )
        )
    }

    private suspend fun createTask(id: String, wfId: String, materialId: String, type: String, status: String) {
        taskDb.create(
            Task(
                id            = id,
                type          = type,
                workflowRunId = wfId,
                materialId    = materialId,
                status        = status,
                createdAt     = nowSec(),
            )
        )
    }

    // ── Q1：queryActive — 无活跃工作流 ───────────────────────────────────────

    /**
     * 证明目的：素材没有任何 WorkflowRun 时，queryActive 返回空列表而非报错。
     */
    @Test
    fun `queryActive returns empty when no active runs exist`() = runBlocking {
        val result = MaterialWorkflowService.queryActive("$matBase-q1-unknown")

        assertEquals(0, result.workflowRuns.size, "未知素材应返回空列表")
    }

    // ── Q2：queryActive — 返回活跃工作流及其子任务 ────────────────────────────

    /**
     * 证明目的：存在 running 状态 WorkflowRun 时，queryActive 正确返回该运行实例
     *           及其两个子任务，前端刷新后可恢复进度展示。
     *
     * 证明过程：
     *   1. 插入 1 个 running WorkflowRun（2 个任务）。
     *   2. 调用 queryActive。
     *   3. 断言返回列表长度 = 1，任务列表长度 = 2，包含正确 ID 和类型。
     */
    @Test
    fun `queryActive returns running workflow with its tasks`() = runBlocking {
        val matId  = "$matBase-q2"
        val wfId   = "wf-q2-running"
        val task1  = "t-q2-download"
        val task2  = "t-q2-transcode"

        createRun(wfId, matId, "running", total = 2)
        createTask(task1, wfId, matId, "DOWNLOAD_BILIBILI_VIDEO", "running")
        createTask(task2, wfId, matId, "TRANSCODE_MP4",          "pending")

        val result = MaterialWorkflowService.queryActive(matId)

        assertEquals(1, result.workflowRuns.size, "应有 1 个活跃工作流")
        val wfWithTasks = result.workflowRuns[0]
        assertEquals(wfId, wfWithTasks.run.id)
        assertEquals("running", wfWithTasks.run.status)
        assertEquals(2, wfWithTasks.tasks.size, "应附带 2 个子任务")
        assertTrue(wfWithTasks.tasks.any { it.id == task1 }, "应包含 download task")
        assertTrue(wfWithTasks.tasks.any { it.id == task2 }, "应包含 transcode task")
    }

    // ── Q3：queryActive — 不返回终态工作流 ───────────────────────────────────

    /**
     * 证明目的：completed / failed / cancelled 三种终态 WorkflowRun 均不应出现在
     *           queryActive 的结果中，仅 running 状态的才会被返回。
     */
    @Test
    fun `queryActive excludes completed, failed and cancelled runs`() = runBlocking {
        val matId = "$matBase-q3"

        createRun("wf-q3-completed",  matId, "completed")
        createRun("wf-q3-failed",     matId, "failed")
        createRun("wf-q3-cancelled",  matId, "cancelled")
        createRun("wf-q3-running",    matId, "running")

        val result = MaterialWorkflowService.queryActive(matId)

        assertEquals(1, result.workflowRuns.size, "终态工作流不应出现在 active 结果中")
        assertEquals("wf-q3-running", result.workflowRuns[0].run.id)
    }

    // ── Q4：queryActive — 多个活跃工作流各带正确子任务 ───────────────────────

    /**
     * 证明目的：存在多个活跃 WorkflowRun 时，每个 WorkflowRun 只携带其自身的子任务，
     *           任务不会串联到其他 WorkflowRun 下（外键隔离）。
     */
    @Test
    fun `queryActive returns multiple active runs each with correct tasks`() = runBlocking {
        val matId = "$matBase-q4"

        createRun("wf-q4-a", matId, "running", total = 1)
        createTask("t-q4-a", "wf-q4-a", matId, "DOWNLOAD_BILIBILI_VIDEO", "running")

        createRun("wf-q4-b", matId, "pending", total = 2)
        createTask("t-q4-b1", "wf-q4-b", matId, "DOWNLOAD_BILIBILI_VIDEO", "pending")
        createTask("t-q4-b2", "wf-q4-b", matId, "TRANSCODE_MP4",          "pending")

        val result = MaterialWorkflowService.queryActive(matId)

        assertEquals(2, result.workflowRuns.size, "应有 2 个活跃工作流")

        val wfA = result.workflowRuns.find { it.run.id == "wf-q4-a" }!!
        val wfB = result.workflowRuns.find { it.run.id == "wf-q4-b" }!!

        assertEquals(1, wfA.tasks.size, "wf-q4-a 应只有 1 个任务")
        assertEquals("t-q4-a", wfA.tasks[0].id)

        assertEquals(2, wfB.tasks.size, "wf-q4-b 应有 2 个任务")
        assertTrue(wfB.tasks.any { it.id == "t-q4-b1" })
        assertTrue(wfB.tasks.any { it.id == "t-q4-b2" })
    }

    // ── Q5：queryHistory — 返回终态工作流，total 正确 ────────────────────────

    /**
     * 证明目的：queryHistory 正确返回 completed / failed 工作流，
     *           total 反映数据库中全部终态记录数，items 附带子任务。
     */
    @Test
    fun `queryHistory returns terminal runs with correct total`() = runBlocking {
        val matId = "$matBase-q5"

        createRun("wf-q5-done",   matId, "completed", total = 1)
        createTask("t-q5-done",   "wf-q5-done", matId, "TRANSCODE_MP4", "completed")

        createRun("wf-q5-fail",   matId, "failed", total = 1)
        createTask("t-q5-fail",   "wf-q5-fail", matId, "DOWNLOAD_BILIBILI_VIDEO", "failed")

        createRun("wf-q5-active", matId, "running")   // 不应出现在历史中

        val result = MaterialWorkflowService.queryHistory(matId, page = 1, pageSize = 10)

        assertEquals(2, result.total, "total 应为终态工作流数量 2")
        assertEquals(2, result.items.size)
        assertTrue(result.items.all { it.run.status in setOf("completed", "failed", "cancelled") })
        assertTrue(result.items.all { it.tasks.size == 1 }, "每个历史工作流应附带 1 个子任务")
    }

    // ── Q6：queryHistory — 不返回活跃工作流 ──────────────────────────────────

    /**
     * 证明目的：running / pending 状态工作流不会出现在 queryHistory 中。
     */
    @Test
    fun `queryHistory excludes active runs`() = runBlocking {
        val matId = "$matBase-q6"

        createRun("wf-q6-running", matId, "running")
        createRun("wf-q6-pending", matId, "pending")
        createRun("wf-q6-done",    matId, "completed")

        val result = MaterialWorkflowService.queryHistory(matId, page = 1, pageSize = 10)

        assertEquals(1, result.total)
        assertEquals("wf-q6-done", result.items[0].run.id)
    }

    // ── Q7：queryHistory — 分页返回正确子集 ──────────────────────────────────

    /**
     * 证明目的：分页参数 page / pageSize 正确控制返回子集，
     *           total 始终反映总量（不随分页变化）。
     *
     * 证明过程：
     *   1. 插入 3 个 completed 工作流。
     *   2. 查询 page=1, pageSize=2 → items 长度 = 2, total = 3。
     *   3. 查询 page=2, pageSize=2 → items 长度 = 1, total = 3。
     */
    @Test
    fun `queryHistory pagination returns correct subset`() = runBlocking {
        val matId = "$matBase-q7"

        createRun("wf-q7-1", matId, "completed")
        createRun("wf-q7-2", matId, "completed")
        createRun("wf-q7-3", matId, "completed")

        val page1 = MaterialWorkflowService.queryHistory(matId, page = 1, pageSize = 2)
        assertEquals(3, page1.total, "total 应为 3")
        assertEquals(2, page1.items.size, "第 1 页应有 2 条")

        val page2 = MaterialWorkflowService.queryHistory(matId, page = 2, pageSize = 2)
        assertEquals(3, page2.total, "total 不随分页变化")
        assertEquals(1, page2.items.size, "第 2 页应有 1 条")
    }
}
