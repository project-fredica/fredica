package com.github.project_fredica.db

// =============================================================================
// TaskDbTest —— task 表基础 CRUD 与并发认领的单元测试
// =============================================================================
//
// 测试范围：TaskDb 的五个核心行为
//   1. createAll / listAll   — 批量写入与全量读取
//   2. idempotency_key 去重  — 相同幂等键只保留一条记录
//   3. claimNext 原子性       — 并发情况下每个任务最多被认领一次
//   4. updateStatus 持久化    — 状态、结果、时间戳均正确落库
//   5. listByPipeline 隔离性  — 按流水线 ID 过滤，不同流水线数据不干扰
//
// 测试环境：每个测试用例独立的 SQLite 临时文件（@BeforeTest 重新创建）。
//
// 为什么用临时文件而非内存数据库（:memory:）：
//   ktorm 使用连接池，每次 db.useConnection{} 都可能打开新连接。
//   :memory: 数据库的每条连接拥有完全独立的数据集——initialize() 在连接 A
//   建的表，连接 B 根本看不见。临时文件让所有连接共享同一份物理文件，
//   测试进程结束后由 JVM 自动删除。
// =============================================================================

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.ktorm.database.Database
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TaskDbTest {

    private lateinit var db: Database
    private lateinit var taskDb: TaskDb
    private lateinit var pipelineDb: PipelineDb

    /**
     * 每个测试前重新创建一个干净的 SQLite 临时文件数据库，保证测试互不干扰。
     * pipeline-1 是所有任务的宿主流水线（task 表有外键约束检查时需要它存在）。
     */
    @BeforeTest
    fun setup() = runBlocking {
        val tmpFile = File.createTempFile("taskdbtest_", ".db").also { it.deleteOnExit() }
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

        pipelineDb.create(
            PipelineInstance(
                id = "pipeline-1", materialId = "material-1",
                template = "TEST", status = "pending",
                totalTasks = 0, doneTasks = 0,
                createdAt = nowSec(),
            )
        )
    }

    // ── 测试 1：批量创建与全量查询 ────────────────────────────────────────────

    /**
     * 证明目的：createAll() 能正确持久化所有任务，listAll() 能完整返回。
     *
     * 证明过程：
     *   1. 用 createAll() 一次性写入 3 条任务。
     *   2. 用 listAll() 读回所有任务，断言数量为 3。
     *   3. 验证每条任务的初始状态均为 "pending"——说明默认值正确落库。
     *
     * 覆盖的边界：批量写入（非逐条）、默认字段值、全量查询无过滤。
     */
    @Test
    fun testCreateAndList() = runBlocking {
        val tasks = (1..3).map { i ->
            Task(
                id = "task-$i", type = "DOWNLOAD_VIDEO",
                pipelineId = "pipeline-1", materialId = "material-1",
                createdAt = nowSec(),
            )
        }
        taskDb.createAll(tasks)

        val all = taskDb.listAll()
        assertEquals(3, all.size, "createAll 写入 3 条，listAll 应返回全部 3 条")
        assertTrue(all.all { it.status == "pending" }, "新建任务的默认状态必须是 pending")
    }

    // ── 测试 2：幂等键去重 ────────────────────────────────────────────────────

    /**
     * 证明目的：相同 idempotency_key 的任务只会保留第一条，重复插入被静默忽略。
     *
     * 证明过程：
     *   1. 插入任务 idem-1，idempotency_key = "unique-key-abc"。
     *   2. 插入任务 idem-2，idempotency_key 相同但 id 不同（模拟重复提交）。
     *   3. 读取全部任务，断言只有 1 条（idem-2 被丢弃）。
     *   4. 验证保留的是 idem-1（先到先得）。
     *
     * 关键实现细节：
     *   SQL 使用 `INSERT OR IGNORE`，它能同时处理 id 冲突和 idempotency_key 冲突。
     *   如果用 `ON CONFLICT(id) DO NOTHING`，idempotency_key 冲突时会抛出异常。
     */
    @Test
    fun testIdempotencyKey() = runBlocking {
        val t1 = Task(
            id = "idem-1", type = "EXTRACT_AUDIO",
            pipelineId = "pipeline-1", materialId = "material-1",
            idempotencyKey = "unique-key-abc",
            createdAt = nowSec(),
        )
        taskDb.create(t1)

        // id 不同，但 idempotency_key 相同——模拟客户端重试导致的重复提交
        val t2 = t1.copy(id = "idem-2")
        taskDb.create(t2)

        val all = taskDb.listAll()
        assertEquals(1, all.size, "相同幂等键的第二次插入应被静默忽略")
        assertEquals("idem-1", all.first().id, "保留的应该是第一次插入的任务")
    }

    // ── 测试 3：claimNext 原子性 ──────────────────────────────────────────────

    /**
     * 证明目的：两个 worker 并发调用 claimNext() 时，每个任务最多被认领一次。
     *
     * 证明过程：
     *   1. 预先写入 3 个任务（atomic-1/2/3）。
     *   2. 同时启动 2 个协程，分别以 worker-1 和 worker-2 的身份调用 claimNext()。
     *   3. 两个协程认领到的任务 ID 收集到 claimedIds 列表（用 Mutex 保护写入）。
     *   4. 断言：claimedIds 的大小等于去重后的大小 → 不存在同一任务被两个 worker 认领。
     *   5. 断言：认领数 ≤ 2（协程数量，不会超认领）。
     *
     * 为什么这能证明原子性：
     *   SQLite 的 `UPDATE ... WHERE id=(SELECT ...)` 在单进程内是原子的——
     *   两个协程即使同时运行该 SQL，SQLite 的写锁保证了只有一个能成功 UPDATE。
     *   如果不是原子操作（先 SELECT 再 UPDATE），两个 worker 可能选中同一行，
     *   导致 claimedIds 中出现重复 ID。
     */
    @Test
    fun testAtomicClaim() = runBlocking {
        val tasks = (1..3).map { i ->
            Task(
                id = "atomic-$i", type = "SPLIT_AUDIO",
                pipelineId = "pipeline-1", materialId = "material-1",
                createdAt = nowSec() + i, // 时间戳递增确保排序稳定
            )
        }
        taskDb.createAll(tasks)

        val claimedIds = mutableListOf<String>()
        val mutex = Mutex() // 保护 claimedIds 的并发写入（List 非线程安全）

        val jobs = (1..2).map { workerId ->
            launch {
                val claimed = taskDb.claimNext("worker-$workerId")
                if (claimed != null) {
                    mutex.lock()
                    try { claimedIds.add(claimed.id) } finally { mutex.unlock() }
                }
            }
        }
        jobs.forEach { it.join() }

        // 核心断言：如果发生双重认领，claimedIds 会有重复元素，toSet() 后大小会变小
        assertEquals(
            claimedIds.size, claimedIds.toSet().size,
            "每个任务只能被认领一次，不允许出现相同 ID"
        )
        assertTrue(claimedIds.size <= 2, "认领数不能超过并发 worker 数量")
    }

    // ── 测试 4：updateStatus 持久化 ───────────────────────────────────────────

    /**
     * 证明目的：updateStatus() 能正确将状态、结果和完成时间戳持久化到数据库。
     *
     * 证明过程：
     *   1. 创建任务 upd-1，初始 status = "pending"。
     *   2. 调用 updateStatus("completed", result=...) 更新状态和结果。
     *   3. 通过 listAll() 重新从数据库读取该任务。
     *   4. 断言三项：
     *      a. status 已变为 "completed"
     *      b. result 字段与写入内容完全一致
     *      c. completed_at 不为 null（说明时间戳被自动记录）
     *
     * 覆盖的边界：
     *   - result 字段包含 JSON 特殊字符（花括号、冒号、引号），验证不会被转义破坏。
     *   - completed_at 的自动填充逻辑（在 updateStatus 内部根据 status 决定是否写入）。
     */
    @Test
    fun testStatusUpdatePersisted() = runBlocking {
        val t = Task(
            id = "upd-1", type = "MERGE_TRANSCRIPTION",
            pipelineId = "pipeline-1", materialId = "material-1",
            createdAt = nowSec(),
        )
        taskDb.create(t)
        taskDb.updateStatus("upd-1", "completed", result = """{"output":"ok"}""")

        val updated = taskDb.listAll().find { it.id == "upd-1" }
        assertNotNull(updated, "更新后的任务应能通过 listAll 查到")
        assertEquals("completed", updated.status, "状态必须已更新为 completed")
        assertEquals("""{"output":"ok"}""", updated.result, "result 字段内容必须与写入完全一致")
        assertTrue(updated.completedAt != null, "completed_at 应在进入终态时自动记录")
    }

    // ── 测试 5：listByPipeline 隔离性 ────────────────────────────────────────

    /**
     * 证明目的：listByPipeline() 只返回属于指定 pipeline 的任务，不同流水线数据互不干扰。
     *
     * 证明过程：
     *   1. 创建两条流水线（pipeline-1 在 setup() 已存在，新建 pipeline-2）。
     *   2. 分别向两条流水线写入任务：pipeline-1 有 2 个，pipeline-2 有 1 个。
     *   3. 查询 pipeline-1 的任务，断言数量为 2 且每条记录的 pipelineId 正确。
     *   4. 查询 pipeline-2 的任务，断言数量为 1 且 ID 为 p2t1。
     *
     * 覆盖的边界：
     *   - WHERE pipeline_id = ? 过滤条件正确生效。
     *   - 两个流水线的任务在同一张表里，过滤不会混淆。
     */
    @Test
    fun testListByPipeline() = runBlocking {
        pipelineDb.create(
            PipelineInstance(
                id = "pipeline-2", materialId = "material-2",
                template = "OTHER", status = "pending",
                totalTasks = 0, doneTasks = 0,
                createdAt = nowSec(),
            )
        )

        // pipeline-1 的 2 个任务
        taskDb.create(Task(id = "p1t1", type = "DOWNLOAD_VIDEO", pipelineId = "pipeline-1", materialId = "material-1", createdAt = nowSec()))
        taskDb.create(Task(id = "p1t2", type = "EXTRACT_AUDIO",  pipelineId = "pipeline-1", materialId = "material-1", createdAt = nowSec()))
        // pipeline-2 的 1 个任务
        taskDb.create(Task(id = "p2t1", type = "DOWNLOAD_VIDEO", pipelineId = "pipeline-2", materialId = "material-2", createdAt = nowSec()))

        val pl1Tasks = taskDb.listByPipeline("pipeline-1")
        assertEquals(2, pl1Tasks.size, "pipeline-1 应返回 2 个任务")
        assertTrue(pl1Tasks.all { it.pipelineId == "pipeline-1" }, "所有任务的 pipelineId 必须是 pipeline-1")

        val pl2Tasks = taskDb.listByPipeline("pipeline-2")
        assertEquals(1, pl2Tasks.size, "pipeline-2 应返回 1 个任务")
        assertEquals("p2t1", pl2Tasks.first().id, "pipeline-2 的任务 ID 必须是 p2t1")
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────────────

    private fun nowSec() = System.currentTimeMillis() / 1000L
}
