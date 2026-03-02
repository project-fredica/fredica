package com.github.project_fredica.db

// =============================================================================
// PipelineDbTest —— 流水线进度联动与幂等控制的单元测试
// =============================================================================
//
// 测试范围：PipelineDb 的六个核心行为
//   1. create / getById       — 创建与查询，验证字段正确落库
//   2. recalculate（全完成）  — 所有子任务 completed → pipeline = completed
//   3. recalculate（部分完成）— 部分子任务 completed → pipeline = running
//   4. recalculate（有失败）  — 任意子任务 failed → pipeline = failed（优先于其他状态）
//   5. cancel                 — pending 任务被取消，running 任务不受影响
//   6. hasActivePipeline      — 活跃流水线阻止重复创建，完成后解除阻塞
//   7. listPaged              — 分页查询（过滤 + 分页 + 倒序）
//
// 测试环境：每个测试用例独立的 SQLite 临时文件（@BeforeTest 重新创建）。
// =============================================================================

import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PipelineDbTest {

    private lateinit var db: Database
    private lateinit var taskDb: TaskDb
    private lateinit var pipelineDb: PipelineDb

    /**
     * 每个测试前重建一个干净的临时文件数据库。
     * pipeline_instance 和 task 表同时初始化，因为 recalculate() 需要读 task 表。
     */
    @BeforeTest
    fun setup() = runBlocking {
        val tmpFile = File.createTempFile("pipelinedbtest_", ".db").also { it.deleteOnExit() }
        db = Database.connect(
            url    = "jdbc:sqlite:${tmpFile.absolutePath}",
            driver = "org.sqlite.JDBC",
        )
        pipelineDb = PipelineDb(db)
        taskDb     = TaskDb(db)
        pipelineDb.initialize()
        taskDb.initialize()
        TaskService.initialize(taskDb)
        PipelineService.initialize(pipelineDb)
    }

    // ── 测试 1：创建与查询 ────────────────────────────────────────────────────

    /**
     * 证明目的：create() 正确持久化所有字段，getById() 能准确读回。
     *
     * 证明过程：
     *   1. 创建一条 totalTasks=3 的流水线。
     *   2. 用 getById() 读回，断言以下字段值与写入一致：
     *      - status = "pending"（默认初始状态）
     *      - totalTasks = 3（写入值）
     *      - doneTasks = 0（尚未执行任何任务）
     */
    @Test
    fun testCreate() = runBlocking {
        pipelineDb.create(pipeline("pl-1", totalTasks = 3))

        val found = pipelineDb.getById("pl-1")
        assertNotNull(found, "刚创建的流水线应能通过 getById 查到")
        assertEquals("pending", found.status, "新建流水线的初始状态应为 pending")
        assertEquals(3, found.totalTasks, "totalTasks 应与写入值一致")
        assertEquals(0, found.doneTasks, "新建流水线的 doneTasks 应为 0")
    }

    // ── 测试 2：recalculate — 全部完成 ───────────────────────────────────────

    /**
     * 证明目的：所有子任务都 completed 后，recalculate() 将 pipeline 状态更新为 completed，
     *           并自动记录 completed_at 时间戳。
     *
     * 证明过程：
     *   1. 创建流水线 + 3 个子任务。
     *   2. 将全部 3 个任务的状态改为 "completed"。
     *   3. 调用 recalculate()。
     *   4. 重新查询流水线，断言：
     *      - status = "completed"
     *      - doneTasks = 3（完成数量统计正确）
     *      - completedAt != null（时间戳被自动写入）
     *
     * 验证的核心逻辑：recalculate() 的状态判定规则中，
     * "所有任务均 completed" 优先级高于 "有 running/pending 任务"。
     */
    @Test
    fun testRecalculate_allCompleted() = runBlocking {
        pipelineDb.create(pipeline("pl-all-done"))
        createTasks("pl-all-done", 3)

        taskDb.listByPipeline("pl-all-done").forEach { taskDb.updateStatus(it.id, "completed") }
        pipelineDb.recalculate("pl-all-done")

        val updated = pipelineDb.getById("pl-all-done")!!
        assertEquals("completed", updated.status, "所有任务完成后 pipeline 应变为 completed")
        assertEquals(3, updated.doneTasks, "doneTasks 应等于全部子任务数量")
        assertTrue(updated.completedAt != null, "进入 completed 状态时应自动记录 completed_at")
    }

    // ── 测试 3：recalculate — 部分完成 ───────────────────────────────────────

    /**
     * 证明目的：部分子任务完成时，recalculate() 将 pipeline 状态更新为 running，
     *           并正确统计 doneTasks 数量。
     *
     * 证明过程：
     *   1. 创建流水线 + 3 个子任务（均为 pending）。
     *   2. 将第 1 个任务改为 "completed"，其余保持 pending。
     *   3. 调用 recalculate()。
     *   4. 断言 status = "running"、doneTasks = 1。
     *
     * 验证的核心逻辑：有 pending/running 任务存在时，pipeline 状态为 "running"，
     * 不管已完成多少任务。
     */
    @Test
    fun testRecalculate_partialDone() = runBlocking {
        pipelineDb.create(pipeline("pl-partial"))
        val tasks = createTasks("pl-partial", 3)

        // 只完成第一个任务，其余两个保持 pending
        taskDb.updateStatus(tasks[0].id, "completed")
        pipelineDb.recalculate("pl-partial")

        val updated = pipelineDb.getById("pl-partial")!!
        assertEquals("running", updated.status, "有任务未完成时 pipeline 应为 running")
        assertEquals(1, updated.doneTasks, "只完成了 1 个任务，doneTasks 应为 1")
    }

    // ── 测试 4：recalculate — 有任务失败 ─────────────────────────────────────

    /**
     * 证明目的：任意子任务进入 failed 状态后，recalculate() 立即将 pipeline 标记为 failed，
     *           即使同时有其他任务已经 completed。
     *
     * 证明过程：
     *   1. 创建流水线 + 2 个子任务。
     *   2. 将第 1 个任务改为 "completed"，第 2 个任务改为 "failed"。
     *   3. 调用 recalculate()。
     *   4. 断言 pipeline status = "failed"。
     *
     * 验证的优先级规则：failed 状态的优先级高于 completed，
     * 只要有一个任务失败，整个 pipeline 就失败（不会因为其他任务完成而掩盖错误）。
     */
    @Test
    fun testRecalculate_oneFailed() = runBlocking {
        pipelineDb.create(pipeline("pl-failed"))
        val tasks = createTasks("pl-failed", 2)

        taskDb.updateStatus(tasks[0].id, "completed")
        taskDb.updateStatus(tasks[1].id, "failed", error = "模拟执行失败")
        pipelineDb.recalculate("pl-failed")

        assertEquals(
            "failed", pipelineDb.getById("pl-failed")!!.status,
            "有子任务失败时，pipeline 应立即变为 failed"
        )
    }

    // ── 测试 5：cancel — 选择性取消 ──────────────────────────────────────────

    /**
     * 证明目的：cancel() 只取消 pending 状态的任务，不干扰正在执行（running）的任务。
     *
     * 证明过程：
     *   1. 创建流水线 + 4 个任务（默认均为 pending）。
     *   2. 手动将第 1 个任务改为 "running"（模拟正在执行）。
     *   3. 调用 cancel()，获取返回值（被取消的任务数）。
     *   4. 断言：
     *      - 返回值 = 3（只有 3 个 pending 任务被取消）
     *      - 查询结果中 running 任务数 = 1（running 的那个未被影响）
     *      - 查询结果中 cancelled 任务数 = 3
     *
     * 验证的设计决策：强行停止 running 任务可能导致文件损坏，
     * 所以 cancel() 设计为"只清理队列，不打断执行中的任务"。
     */
    @Test
    fun testCancel() = runBlocking {
        pipelineDb.create(pipeline("pl-cancel"))
        val tasks = createTasks("pl-cancel", 4)

        // 将第一个任务改为 running，模拟 worker 已经开始执行
        taskDb.updateStatus(tasks[0].id, "running")

        val cancelledCount = pipelineDb.cancel("pl-cancel")
        assertEquals(3, cancelledCount, "只有 pending 任务应被取消，共 3 个")

        val all = taskDb.listByPipeline("pl-cancel")
        assertEquals(1, all.count { it.status == "running" },   "running 任务不应被取消")
        assertEquals(3, all.count { it.status == "cancelled" }, "3 个 pending 任务应变为 cancelled")
    }

    // ── 测试 6：hasActivePipeline — 幂等控制 ──────────────────────────────────

    /**
     * 证明目的：活跃流水线存在时 hasActivePipeline() 返回 true，
     *           流水线完成后返回 false，允许重新创建。
     *
     * 证明过程（分两阶段验证）：
     *
     * 阶段一（活跃期）：
     *   1. 创建一条 status=pending 的流水线（material-1 + TEMPLATE_A）。
     *   2. 调用 hasActivePipeline("material-1", "TEMPLATE_A")，断言返回 true。
     *   → 证明：活跃状态的流水线会阻止重复创建，防止同一素材同时运行两条流水线。
     *
     * 阶段二（完成后）：
     *   3. 创建并完成该流水线的唯一子任务。
     *   4. 调用 recalculate() 将流水线推进到 completed 状态。
     *   5. 再次调用 hasActivePipeline()，断言返回 false。
     *   → 证明：终态（completed/failed/cancelled）的流水线不再阻塞，可以重新提交。
     */
    @Test
    fun testIdempotentPipeline() = runBlocking {
        // 阶段一：活跃期
        pipelineDb.create(pipeline("pl-idem"))
        assertTrue(
            pipelineDb.hasActivePipeline("material-1", "TEMPLATE_A"),
            "流水线处于 pending 状态时，应视为活跃，阻止重复创建"
        )

        // 阶段二：完成流水线
        createTasks("pl-idem", 1)
        taskDb.updateStatus(taskDb.listByPipeline("pl-idem").first().id, "completed")
        pipelineDb.recalculate("pl-idem")

        assertFalse(
            pipelineDb.hasActivePipeline("material-1", "TEMPLATE_A"),
            "流水线完成后应不再视为活跃，允许重新创建"
        )
    }

    // ── 测试 7：listPaged —— 分页查询 ─────────────────────────────────────────

    /**
     * 证明目的：listPaged() 能正确实现分页、状态过滤和倒序排列。
     *
     * 证明过程：
     *   1. 插入 3 条流水线（status 各不同：pending / running / completed）。
     *   2. 不加过滤，pageSize=100 → 应返回全部 3 条，total=3。
     *   3. 过滤 status=running  → 只返回 1 条。
     *   4. page=1, pageSize=2   → items.size=2，totalPages=2。
     *   5. page=2, pageSize=2   → items.size=1（最后一页）。
     */
    @Test
    fun testListPaged() = runBlocking {
        pipelineDb.create(pipeline("pl-page-1").copy(status = "pending"))
        pipelineDb.create(pipeline("pl-page-2").copy(status = "running"))
        pipelineDb.create(pipeline("pl-page-3").copy(status = "completed"))

        // 全量查询
        val all = pipelineDb.listPaged(PipelineListQuery(pageSize = 100))
        assertTrue(all.total >= 3, "total 应 >= 3")
        assertTrue(all.items.size >= 3, "items.size 应 >= 3")

        // 按状态过滤
        val running = pipelineDb.listPaged(PipelineListQuery(status = "running", pageSize = 100))
        assertEquals(1, running.total, "过滤 running 应只有 1 条")

        // 分页：第 1 页，每页 2 条
        val page1 = pipelineDb.listPaged(PipelineListQuery(page = 1, pageSize = 2))
        assertEquals(2, page1.items.size, "第 1 页应返回 2 条")
        assertEquals(2, page1.totalPages, "总页数应为 2")

        // 分页：第 2 页
        val page2 = pipelineDb.listPaged(PipelineListQuery(page = 2, pageSize = 2))
        assertEquals(1, page2.items.size, "第 2 页应返回 1 条")
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────────────

    private fun nowSec() = System.currentTimeMillis() / 1000L

    /** 构造一个标准测试流水线（material-1 + TEMPLATE_A）。 */
    private fun pipeline(id: String, totalTasks: Int = 3) = PipelineInstance(
        id = id, materialId = "material-1",
        template = "TEMPLATE_A", status = "pending",
        totalTasks = totalTasks, doneTasks = 0,
        createdAt = nowSec(),
    )

    /**
     * 为指定流水线批量创建 [count] 个任务，createdAt 依次递增 1 秒确保排序稳定。
     * 返回已创建的任务列表，便于测试中直接操作具体任务 ID。
     */
    private suspend fun createTasks(pipelineId: String, count: Int): List<Task> {
        val tasks = (1..count).map { i ->
            Task(
                id = "$pipelineId-t$i", type = "DOWNLOAD_VIDEO",
                pipelineId = pipelineId, materialId = "material-1",
                createdAt = nowSec() + i,
            )
        }
        taskDb.createAll(tasks)
        return tasks
    }
}
