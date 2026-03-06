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
