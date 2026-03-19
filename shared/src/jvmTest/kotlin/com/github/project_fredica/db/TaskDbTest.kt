package com.github.project_fredica.db

// =============================================================================
// TaskDbTest —— task 表基础 CRUD 与并发认领的单元测试
// =============================================================================
//
// 测试范围：TaskDb 的五个核心行为
//   1. createAll / listAll      — 批量写入与全量读取
//   2. idempotency_key 去重     — 相同幂等键只保留一条记录
//   3. claimNext 原子性          — 并发情况下每个任务最多被认领一次
//   4. updateStatus 持久化       — 状态、结果、时间戳均正确落库
//   5. listByWorkflowRun 隔离性 — 按工作流运行实例 ID 过滤，不同实例数据不干扰
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
    private lateinit var workflowRunDb: WorkflowRunDb

    /**
     * 每个测试前重新创建一个干净的 SQLite 临时文件数据库，保证测试互不干扰。
     * wr-1 是所有任务的宿主工作流运行实例。
     */
    @BeforeTest
    fun setup() = runBlocking {
        val tmpFile = File.createTempFile("taskdbtest_", ".db").also { it.deleteOnExit() }
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

        workflowRunDb.create(
            WorkflowRun(
                id = "wr-1", materialId = "material-1",
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
                workflowRunId = "wr-1", materialId = "material-1",
                createdAt = nowSec(),
            )
        }
        taskDb.createAll(tasks)

        val all = taskDb.listAll()
        assertEquals(3, all.items.size, "createAll 写入 3 条，listAll 应返回全部 3 条")
        assertTrue(all.items.all { it.status == "pending" }, "新建任务的默认状态必须是 pending")
    }

    // ── 测试 2：幂等键去重 ────────────────────────────────────────────────────

    /**
     * 证明目的：相同 idempotency_key 只对活跃任务（非终态）去重；已完成的任务不阻塞新任务。
     *
     * 用例 A：活跃任务去重
     *   1. 插入 idem-1（pending），idempotency_key = "key-abc"。
     *   2. 插入 idem-2，相同 key——应被跳过。
     *   3. 断言只有 1 条。
     *
     * 用例 B：已完成后可重新插入
     *   1. 将 idem-1 标记为 completed。
     *   2. 插入 idem-3，相同 key——应成功插入。
     *   3. 断言共 2 条（idem-1 completed + idem-3 pending）。
     */
    @Test
    fun testIdempotencyKeyActiveOnly() = runBlocking {
        val t1 = Task(
            id = "idem-1", type = "EXTRACT_AUDIO",
            workflowRunId = "wr-1", materialId = "material-1",
            idempotencyKey = "key-abc",
            createdAt = nowSec(),
        )
        taskDb.create(t1)

        // 用例 A：活跃任务去重
        val t2 = t1.copy(id = "idem-2")
        taskDb.create(t2)
        assertEquals(1, taskDb.listAll().items.size, "活跃任务相同幂等键应被跳过")

        // 用例 B：完成后可重新插入
        taskDb.updateStatus(t1.id, "completed")
        val t3 = t1.copy(id = "idem-3")
        taskDb.create(t3)
        assertEquals(2, taskDb.listAll().items.size, "已完成任务不应阻塞相同幂等键的新任务")
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
                workflowRunId = "wr-1", materialId = "material-1",
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
     */
    @Test
    fun testStatusUpdatePersisted() = runBlocking {
        val t = Task(
            id = "upd-1", type = "MERGE_TRANSCRIPTION",
            workflowRunId = "wr-1", materialId = "material-1",
            createdAt = nowSec(),
        )
        taskDb.create(t)
        taskDb.updateStatus("upd-1", "completed", result = """{"output":"ok"}""")

        val updated = taskDb.listAll().items.find { it.id == "upd-1" }
        assertNotNull(updated, "更新后的任务应能通过 listAll 查到")
        assertEquals("completed", updated.status, "状态必须已更新为 completed")
        assertEquals("""{"output":"ok"}""", updated.result, "result 字段内容必须与写入完全一致")
        assertTrue(updated.completedAt != null, "completed_at 应在进入终态时自动记录")
    }

    // ── 测试 5：listByWorkflowRun 隔离性 ─────────────────────────────────────

    /**
     * 证明目的：listByWorkflowRun() 只返回属于指定运行实例的任务，不同实例数据互不干扰。
     *
     * 证明过程：
     *   1. 创建两个运行实例（wr-1 在 setup() 已存在，新建 wr-2）。
     *   2. 分别向两个实例写入任务：wr-1 有 2 个，wr-2 有 1 个。
     *   3. 查询 wr-1 的任务，断言数量为 2 且每条记录的 workflowRunId 正确。
     *   4. 查询 wr-2 的任务，断言数量为 1 且 ID 为 wr2t1。
     *
     * 覆盖的边界：WHERE workflow_run_id = ? 过滤条件正确生效。
     */
    @Test
    fun testListByWorkflowRun() = runBlocking {
        workflowRunDb.create(
            WorkflowRun(
                id = "wr-2", materialId = "material-2",
                template = "OTHER", status = "pending",
                totalTasks = 0, doneTasks = 0,
                createdAt = nowSec(),
            )
        )

        // wr-1 的 2 个任务
        taskDb.create(Task(id = "wr1t1", type = "DOWNLOAD_VIDEO", workflowRunId = "wr-1", materialId = "material-1", createdAt = nowSec()))
        taskDb.create(Task(id = "wr1t2", type = "EXTRACT_AUDIO",  workflowRunId = "wr-1", materialId = "material-1", createdAt = nowSec()))
        // wr-2 的 1 个任务
        taskDb.create(Task(id = "wr2t1", type = "DOWNLOAD_VIDEO", workflowRunId = "wr-2", materialId = "material-2", createdAt = nowSec()))

        val wr1Tasks = taskDb.listByWorkflowRun("wr-1")
        assertEquals(2, wr1Tasks.size, "wr-1 应返回 2 个任务")
        assertTrue(wr1Tasks.all { it.workflowRunId == "wr-1" }, "所有任务的 workflowRunId 必须是 wr-1")

        val wr2Tasks = taskDb.listByWorkflowRun("wr-2")
        assertEquals(1, wr2Tasks.size, "wr-2 应返回 1 个任务")
        assertEquals("wr2t1", wr2Tasks.first().id, "wr-2 的任务 ID 必须是 wr2t1")
    }

    // ── 测试 6：resetStaleTasks — 取消所有非终态任务 ──────────────────────────

    /**
     * 证明目的：resetStaleTasks() 在应用重启时，将 running / claimed / pending
     *           三种非终态任务全部取消，并返回受影响的 workflowRunId 集合。
     *
     * 证明过程：
     *   1. 准备 wr-stale（包含 3 个任务，手动设置为 running/claimed/pending）
     *      + wr-done（仅含 completed 任务，不应受影响）。
     *   2. 调用 resetStaleTasks()。
     *   3. 断言：
     *      a. 返回值中包含 wr-stale，不包含 wr-done。
     *      b. wr-stale 的 3 个任务全部变为 cancelled。
     *      c. wr-done 的任务仍为 completed。
     *
     * 修复背景：
     *   原实现只处理 running→failed 和 claimed→pending，未处理 pending→cancelled。
     *   pending 任务因上游已取消而永远无法通过 claimNext() 的 DAG 校验，
     *   会导致 WorkflowRun 永久停留在 running 状态。
     */
    @Test
    fun testResetStaleTasks_cancelsAllNonTerminal() = runBlocking {
        // wr-stale：含 running / claimed / pending 三种状态的任务
        workflowRunDb.create(
            WorkflowRun(
                id = "wr-stale", materialId = "material-1",
                template = "TEST", status = "running",
                totalTasks = 3, doneTasks = 0, createdAt = nowSec(),
            )
        )
        val runningTask = Task(
            id = "stale-running", type = "DOWNLOAD_VIDEO",
            workflowRunId = "wr-stale", materialId = "material-1",
            createdAt = nowSec(),
        )
        val claimedTask = Task(
            id = "stale-claimed", type = "EXTRACT_AUDIO",
            workflowRunId = "wr-stale", materialId = "material-1",
            createdAt = nowSec(),
        )
        val pendingTask = Task(
            id = "stale-pending", type = "TRANSCRIBE",
            workflowRunId = "wr-stale", materialId = "material-1",
            createdAt = nowSec(),
        )
        taskDb.createAll(listOf(runningTask, claimedTask, pendingTask))
        // 手动将状态设置为 running / claimed（create 后默认 pending）
        taskDb.updateStatus("stale-running", "running")
        taskDb.updateStatus("stale-claimed", "claimed")
        // stale-pending 保持 pending（默认值）

        // wr-done：仅含 completed 任务，不应受影响
        workflowRunDb.create(
            WorkflowRun(
                id = "wr-done", materialId = "material-1",
                template = "TEST", status = "completed",
                totalTasks = 1, doneTasks = 1, createdAt = nowSec(),
            )
        )
        val completedTask = Task(
            id = "done-task", type = "DOWNLOAD_VIDEO",
            workflowRunId = "wr-done", materialId = "material-1",
            createdAt = nowSec(),
        )
        taskDb.create(completedTask)
        taskDb.updateStatus("done-task", "completed")

        // 执行
        val affected = taskDb.resetStaleTasks()

        // 断言 1：返回值正确
        assertTrue("wr-stale" in affected, "wr-stale 有非终态任务，应出现在返回集合中")
        assertTrue("wr-done" !in affected, "wr-done 无非终态任务，不应出现在返回集合中")

        // 断言 2：wr-stale 的所有任务均已取消
        val staleTasks = taskDb.listByWorkflowRun("wr-stale")
        assertTrue(staleTasks.all { it.status == "cancelled" },
            "wr-stale 中 running/claimed/pending 三种任务均应被取消，实际状态：${staleTasks.map { "${it.id}:${it.status}" }}"
        )

        // 断言 3：running 任务的 error 字段包含重启标记
        val formerRunning = staleTasks.find { it.id == "stale-running" }!!
        assertEquals("APP_RESTARTED", formerRunning.error, "running 任务取消后应记录 APP_RESTARTED 原因")

        // 断言 4：completed 任务不受影响
        val doneTask = taskDb.listByWorkflowRun("wr-done").first()
        assertEquals("completed", doneTask.status, "已完成的任务不应被 resetStaleTasks 修改")
    }

    // ── 测试 7：快照非终态任务 ────────────────────────────────────────────────

    /**
     * 证明目的：snapshotNonTerminalTasks() 返回 running / claimed / pending 三种状态的任务，
     * 已 completed 的任务不出现在结果中。
     */
    @Test
    fun testSnapshotNonTerminalTasks() = runBlocking {
        // 准备：写入各种状态的任务
        taskDb.createAll(listOf(
            Task(id = "snap-pending",   type = "DOWNLOAD_VIDEO", workflowRunId = "wr-1", materialId = "material-1", createdAt = nowSec()),
            Task(id = "snap-claimed",   type = "EXTRACT_AUDIO",  workflowRunId = "wr-1", materialId = "material-1", createdAt = nowSec()),
            Task(id = "snap-running",   type = "TRANSCODE_MP4",  workflowRunId = "wr-1", materialId = "material-1", createdAt = nowSec()),
            Task(id = "snap-completed", type = "FETCH_SUBTITLE", workflowRunId = "wr-1", materialId = "material-1", createdAt = nowSec()),
        ))
        taskDb.updateStatus("snap-claimed",   "claimed")
        taskDb.updateStatus("snap-running",   "running")
        taskDb.updateStatus("snap-completed", "completed")

        // 执行
        val snapshot = taskDb.snapshotNonTerminalTasks()

        // 断言
        val ids = snapshot.map { it.id }.toSet()
        assertTrue("snap-pending" in ids,   "pending 任务应出现在快照中")
        assertTrue("snap-claimed" in ids,   "claimed 任务应出现在快照中")
        assertTrue("snap-running" in ids,   "running 任务应出现在快照中")
        assertTrue("snap-completed" !in ids, "completed 任务不应出现在快照中")
        assertEquals(3, snapshot.size, "快照应恰好包含 3 条非终态任务")
    }

    // ── 测试 8：cancelBlockedTasks — 依赖失败级联取消 ─────────────────────────

    /**
     * 证明目的：cancelBlockedTasks() 能正确找出"依赖中存在 failed 任务"的 pending 任务
     *           并将其取消，同时不影响无依赖或依赖已完成的任务。
     *
     * 场景：A → B → C（B 依赖 A，C 依赖 B）
     *   - A 执行失败（failed）
     *   - B 依赖 A，应被级联取消
     *   - C 依赖 B，B 取消后 C 也应被级联取消（第二次调用）
     *   - D 无依赖，不受影响
     *
     * 证明过程：
     *   1. 创建 A/B/C/D 四个任务，设置依赖关系。
     *   2. 将 A 标记为 failed。
     *   3. 第一次调用 cancelBlockedTasks()：B 应被取消（依赖 A=failed）。
     *   4. 第二次调用 cancelBlockedTasks()：C 应被取消（依赖 B=cancelled）。
     *   5. D 始终保持 pending。
     */
    @Test
    fun testCancelBlockedTasks_chainPropagation() = runBlocking {
        // 准备：A → B → C，D 无依赖
        val taskA = Task(id = "chain-a", type = "STEP_A", workflowRunId = "wr-1", materialId = "material-1", createdAt = nowSec())
        val taskB = Task(id = "chain-b", type = "STEP_B", workflowRunId = "wr-1", materialId = "material-1", dependsOn = """["chain-a"]""", createdAt = nowSec() + 1)
        val taskC = Task(id = "chain-c", type = "STEP_C", workflowRunId = "wr-1", materialId = "material-1", dependsOn = """["chain-b"]""", createdAt = nowSec() + 2)
        val taskD = Task(id = "chain-d", type = "STEP_D", workflowRunId = "wr-1", materialId = "material-1", createdAt = nowSec() + 3)
        taskDb.createAll(listOf(taskA, taskB, taskC, taskD))

        // A 执行失败
        taskDb.updateStatus("chain-a", "failed", error = "模拟失败")

        // 第一次级联取消：B 依赖 A(failed)，应被取消
        val round1 = taskDb.cancelBlockedTasks("wr-1")
        assertEquals(listOf("chain-b"), round1.sorted(), "第一轮应取消 chain-b（依赖 chain-a=failed）")

        val afterRound1 = taskDb.listByWorkflowRun("wr-1")
        assertEquals("failed",    afterRound1.find { it.id == "chain-a" }!!.status)
        assertEquals("cancelled", afterRound1.find { it.id == "chain-b" }!!.status)
        assertEquals("pending",   afterRound1.find { it.id == "chain-c" }!!.status, "C 尚未被取消（B 刚变 cancelled）")
        assertEquals("pending",   afterRound1.find { it.id == "chain-d" }!!.status, "D 无依赖，不受影响")

        // 第二次级联取消：C 依赖 B(cancelled)，应被取消
        val round2 = taskDb.cancelBlockedTasks("wr-1")
        assertEquals(listOf("chain-c"), round2.sorted(), "第二轮应取消 chain-c（依赖 chain-b=cancelled）")

        val afterRound2 = taskDb.listByWorkflowRun("wr-1")
        assertEquals("cancelled", afterRound2.find { it.id == "chain-c" }!!.status)
        assertEquals("pending",   afterRound2.find { it.id == "chain-d" }!!.status, "D 始终不受影响")
    }

    // ── 测试 9：cancelBlockedTasks — 依赖取消级联取消 ─────────────────────────

    /**
     * 证明目的：cancelBlockedTasks() 对"依赖中存在 cancelled 任务"的 pending 任务
     *           同样生效（不仅限于 failed）。
     *
     * 场景：A（cancelled）→ B（pending），调用后 B 应被取消。
     */
    @Test
    fun testCancelBlockedTasks_cancelledDependency() = runBlocking {
        val taskA = Task(id = "dep-a", type = "STEP_A", workflowRunId = "wr-1", materialId = "material-1", createdAt = nowSec())
        val taskB = Task(id = "dep-b", type = "STEP_B", workflowRunId = "wr-1", materialId = "material-1", dependsOn = """["dep-a"]""", createdAt = nowSec() + 1)
        taskDb.createAll(listOf(taskA, taskB))

        taskDb.updateStatus("dep-a", "cancelled")

        val cancelled = taskDb.cancelBlockedTasks("wr-1")
        assertEquals(listOf("dep-b"), cancelled, "依赖 cancelled 任务的 pending 任务应被取消")

        val b = taskDb.listByWorkflowRun("wr-1").find { it.id == "dep-b" }!!
        assertEquals("cancelled",        b.status,    "dep-b 状态应为 cancelled")
        assertEquals("DEPENDENCY_FAILED", b.errorType, "error_type 应标记为 DEPENDENCY_FAILED")
        assertEquals("DEPENDENCY_FAILED", b.error,     "error 应标记为 DEPENDENCY_FAILED")
    }

    // ── 测试 10：cancelBlockedTasks — 不影响无依赖或依赖已完成的任务 ───────────

    /**
     * 证明目的：cancelBlockedTasks() 的幂等性与精确性——
     *   - 无依赖的 pending 任务不受影响
     *   - 依赖全部 completed 的 pending 任务不受影响
     *   - 已是终态的任务不受影响
     */
    @Test
    fun testCancelBlockedTasks_doesNotAffectUnblockedTasks() = runBlocking {
        val taskA = Task(id = "safe-a", type = "STEP_A", workflowRunId = "wr-1", materialId = "material-1", createdAt = nowSec())
        val taskB = Task(id = "safe-b", type = "STEP_B", workflowRunId = "wr-1", materialId = "material-1", dependsOn = """["safe-a"]""", createdAt = nowSec() + 1)
        val taskC = Task(id = "safe-c", type = "STEP_C", workflowRunId = "wr-1", materialId = "material-1", createdAt = nowSec() + 2)
        taskDb.createAll(listOf(taskA, taskB, taskC))

        // A 完成，B 依赖 A(completed)，C 无依赖
        taskDb.updateStatus("safe-a", "completed")

        val cancelled = taskDb.cancelBlockedTasks("wr-1")
        assertTrue(cancelled.isEmpty(), "没有被阻塞的任务，返回列表应为空")

        val tasks = taskDb.listByWorkflowRun("wr-1")
        assertEquals("completed", tasks.find { it.id == "safe-a" }!!.status)
        assertEquals("pending",   tasks.find { it.id == "safe-b" }!!.status, "B 依赖已完成，不应被取消")
        assertEquals("pending",   tasks.find { it.id == "safe-c" }!!.status, "C 无依赖，不应被取消")
    }

    // ── 测试 11：cancelBlockedTasks — 菱形依赖，A 失败后 B/C/D 均被级联取消 ────

    /**
     * 证明目的：菱形依赖结构中，根节点失败后所有下游节点均被正确级联取消。
     *
     * 场景（菱形 DAG）：
     *        A（失败）
     *       / \
     *      B   C
     *       \ /
     *        D
     *
     * B 依赖 A，C 依赖 A，D 依赖 B 和 C。
     * A 失败后：
     *   - 第一轮 cancelBlockedTasks：B 和 C 被取消（直接依赖 A=failed）
     *   - 第二轮 cancelBlockedTasks：D 被取消（依赖 B=cancelled 且 C=cancelled）
     *
     * 证明过程：
     *   1. 创建菱形依赖结构。
     *   2. A 标记为 failed。
     *   3. 第一轮：断言 B、C 被取消，D 仍为 pending。
     *   4. 第二轮：断言 D 被取消。
     */
    @Test
    fun testCancelBlockedTasks_diamondDependency() = runBlocking {
        val taskA = Task(id = "dia-a", type = "STEP_A", workflowRunId = "wr-1", materialId = "material-1", createdAt = nowSec())
        val taskB = Task(id = "dia-b", type = "STEP_B", workflowRunId = "wr-1", materialId = "material-1", dependsOn = """["dia-a"]""",         createdAt = nowSec() + 1)
        val taskC = Task(id = "dia-c", type = "STEP_C", workflowRunId = "wr-1", materialId = "material-1", dependsOn = """["dia-a"]""",         createdAt = nowSec() + 2)
        val taskD = Task(id = "dia-d", type = "STEP_D", workflowRunId = "wr-1", materialId = "material-1", dependsOn = """["dia-b","dia-c"]""", createdAt = nowSec() + 3)
        taskDb.createAll(listOf(taskA, taskB, taskC, taskD))

        taskDb.updateStatus("dia-a", "failed", error = "根节点失败")

        // 第一轮：B 和 C 被取消
        val round1 = taskDb.cancelBlockedTasks("wr-1").sorted()
        assertEquals(listOf("dia-b", "dia-c"), round1, "第一轮应取消 B 和 C（均直接依赖 A=failed）")

        val afterRound1 = taskDb.listByWorkflowRun("wr-1").associateBy { it.id }
        assertEquals("pending", afterRound1["dia-d"]!!.status, "D 的直接依赖 B/C 刚变 cancelled，第一轮不处理 D")

        // 第二轮：D 被取消
        val round2 = taskDb.cancelBlockedTasks("wr-1")
        assertEquals(listOf("dia-d"), round2, "第二轮应取消 D（依赖 B=cancelled 且 C=cancelled）")

        val afterRound2 = taskDb.listByWorkflowRun("wr-1").associateBy { it.id }
        assertEquals("cancelled", afterRound2["dia-d"]!!.status)
        assertEquals("DEPENDENCY_FAILED", afterRound2["dia-d"]!!.errorType)
    }

    // ── 测试 12：cancelBlockedTasks — 部分依赖失败（多前置任务中有一个失败）────

    /**
     * 证明目的：任务 B 依赖 A 和 X，A 失败但 X 已完成，B 仍应被取消。
     *
     * 语义：DAG 调度要求所有前置任务均 completed 才能执行，
     *       只要有一个前置任务失败，该任务就永远无法执行，应被取消。
     *
     * 场景：
     *   A（failed）─┐
     *               ├→ B（pending，应被取消）
     *   X（completed）─┘
     */
    @Test
    fun testCancelBlockedTasks_partialDependencyFailed() = runBlocking {
        val taskA = Task(id = "part-a", type = "STEP_A", workflowRunId = "wr-1", materialId = "material-1", createdAt = nowSec())
        val taskX = Task(id = "part-x", type = "STEP_X", workflowRunId = "wr-1", materialId = "material-1", createdAt = nowSec() + 1)
        val taskB = Task(id = "part-b", type = "STEP_B", workflowRunId = "wr-1", materialId = "material-1", dependsOn = """["part-a","part-x"]""", createdAt = nowSec() + 2)
        taskDb.createAll(listOf(taskA, taskX, taskB))

        taskDb.updateStatus("part-a", "failed")
        taskDb.updateStatus("part-x", "completed")

        val cancelled = taskDb.cancelBlockedTasks("wr-1")
        assertEquals(listOf("part-b"), cancelled, "B 的前置任务 A 已失败，即使 X 完成，B 也应被取消")

        val b = taskDb.listByWorkflowRun("wr-1").find { it.id == "part-b" }!!
        assertEquals("cancelled",        b.status)
        assertEquals("DEPENDENCY_FAILED", b.errorType)
    }

    // ── 测试 13：cancelBlockedTasks — 混合终态，成功链不受影响 ─────────────────

    /**
     * 证明目的：同一 WorkflowRun 中，失败链的级联取消不影响独立的成功链。
     *
     * 场景：
     *   失败链：F1（failed）→ F2（pending，应被取消）
     *   成功链：S1（completed）→ S2（pending，不受影响，可被认领）
     *   独立任务：I（pending，无依赖，不受影响）
     */
    @Test
    fun testCancelBlockedTasks_mixedChains_successChainUnaffected() = runBlocking {
        // 失败链
        val taskF1 = Task(id = "mix-f1", type = "FAIL_STEP", workflowRunId = "wr-1", materialId = "material-1", createdAt = nowSec())
        val taskF2 = Task(id = "mix-f2", type = "FAIL_DOWN", workflowRunId = "wr-1", materialId = "material-1", dependsOn = """["mix-f1"]""", createdAt = nowSec() + 1)
        // 成功链
        val taskS1 = Task(id = "mix-s1", type = "OK_STEP",   workflowRunId = "wr-1", materialId = "material-1", createdAt = nowSec() + 2)
        val taskS2 = Task(id = "mix-s2", type = "OK_DOWN",   workflowRunId = "wr-1", materialId = "material-1", dependsOn = """["mix-s1"]""", createdAt = nowSec() + 3)
        // 独立任务
        val taskI  = Task(id = "mix-i",  type = "INDEP",     workflowRunId = "wr-1", materialId = "material-1", createdAt = nowSec() + 4)
        taskDb.createAll(listOf(taskF1, taskF2, taskS1, taskS2, taskI))

        taskDb.updateStatus("mix-f1", "failed")
        taskDb.updateStatus("mix-s1", "completed")

        val cancelled = taskDb.cancelBlockedTasks("wr-1")
        assertEquals(listOf("mix-f2"), cancelled, "只有 F2 应被取消")

        val tasks = taskDb.listByWorkflowRun("wr-1").associateBy { it.id }
        assertEquals("cancelled", tasks["mix-f2"]!!.status, "F2 应被取消")
        assertEquals("pending",   tasks["mix-s2"]!!.status, "S2 依赖已完成的 S1，不应被取消")
        assertEquals("pending",   tasks["mix-i"]!!.status,  "I 无依赖，不应被取消")
    }

    // ── 测试 14：cancelBlockedTasks — 多 WorkflowRun 隔离 ────────────────────

    /**
     * 证明目的：cancelBlockedTasks(wfId) 只处理指定 WorkflowRun 的任务，
     *           不影响其他 WorkflowRun 中的同名依赖结构。
     *
     * 场景：
     *   wr-1：A1（failed）→ B1（pending，应被取消）
     *   wr-2：A2（failed）→ B2（pending，不应被 wr-1 的调用影响）
     */
    @Test
    fun testCancelBlockedTasks_multiWorkflowIsolation() = runBlocking {
        // 创建第二个 WorkflowRun
        workflowRunDb.create(
            WorkflowRun(id = "wr-2", materialId = "material-2", template = "TEST", status = "running", totalTasks = 2, doneTasks = 0, createdAt = nowSec())
        )

        // wr-1 的任务链
        val taskA1 = Task(id = "iso-a1", type = "STEP_A", workflowRunId = "wr-1", materialId = "material-1", createdAt = nowSec())
        val taskB1 = Task(id = "iso-b1", type = "STEP_B", workflowRunId = "wr-1", materialId = "material-1", dependsOn = """["iso-a1"]""", createdAt = nowSec() + 1)
        // wr-2 的任务链（结构相同，但属于不同 WF）
        val taskA2 = Task(id = "iso-a2", type = "STEP_A", workflowRunId = "wr-2", materialId = "material-2", createdAt = nowSec())
        val taskB2 = Task(id = "iso-b2", type = "STEP_B", workflowRunId = "wr-2", materialId = "material-2", dependsOn = """["iso-a2"]""", createdAt = nowSec() + 1)
        taskDb.createAll(listOf(taskA1, taskB1, taskA2, taskB2))

        taskDb.updateStatus("iso-a1", "failed")
        taskDb.updateStatus("iso-a2", "failed")

        // 只对 wr-1 调用 cancelBlockedTasks
        val cancelled = taskDb.cancelBlockedTasks("wr-1")
        assertEquals(listOf("iso-b1"), cancelled, "只应取消 wr-1 的 B1")

        val b2 = taskDb.listByWorkflowRun("wr-2").find { it.id == "iso-b2" }!!
        assertEquals("pending", b2.status, "wr-2 的 B2 不应受 wr-1 调用的影响")
    }

    // ── 测试 15：claimNext 不认领被阻塞的任务 ────────────────────────────────

    /**
     * 证明目的：依赖失败后，被阻塞的 pending 任务不会被 claimNext() 认领，
     *           只有无依赖或依赖已完成的任务才能被认领。
     *
     * 场景：
     *   A（failed）→ B（pending，被阻塞，不可认领）
     *   C（pending，无依赖，可认领）
     *
     * 证明过程：
     *   1. 创建 A/B/C，A 标记为 failed。
     *   2. 调用 claimNext()，断言认领到的是 C 而非 B。
     *   3. 再次调用 claimNext()，断言返回 null（B 被阻塞，C 已被认领）。
     */
    @Test
    fun testClaimNext_doesNotClaimBlockedTask() = runBlocking {
        val taskA = Task(id = "blk-a", type = "STEP_A", workflowRunId = "wr-1", materialId = "material-1", createdAt = nowSec())
        val taskB = Task(id = "blk-b", type = "STEP_B", workflowRunId = "wr-1", materialId = "material-1", dependsOn = """["blk-a"]""", createdAt = nowSec() + 1)
        val taskC = Task(id = "blk-c", type = "STEP_C", workflowRunId = "wr-1", materialId = "material-1", createdAt = nowSec() + 2)
        taskDb.createAll(listOf(taskA, taskB, taskC))

        taskDb.updateStatus("blk-a", "failed")

        // 第一次认领：应得到 C（无依赖），不应得到 B（依赖 A=failed）
        val first = taskDb.claimNext("worker-1")
        assertNotNull(first, "应能认领到 C")
        assertEquals("blk-c", first.id, "认领到的应是无依赖的 C，而非被阻塞的 B")

        // 第二次认领：B 被阻塞，队列为空
        val second = taskDb.claimNext("worker-1")
        assertTrue(second == null, "B 被阻塞无法认领，队列应为空")
    }

    // ── 测试 16：cancelBlockedTasks 幂等性 ───────────────────────────────────

    /**
     * 证明目的：多次调用 cancelBlockedTasks() 是幂等的——
     *           第一次取消后，后续调用不会重复处理已是终态的任务。
     */
    @Test
    fun testCancelBlockedTasks_idempotent() = runBlocking {
        val taskA = Task(id = "idem2-a", type = "STEP_A", workflowRunId = "wr-1", materialId = "material-1", createdAt = nowSec())
        val taskB = Task(id = "idem2-b", type = "STEP_B", workflowRunId = "wr-1", materialId = "material-1", dependsOn = """["idem2-a"]""", createdAt = nowSec() + 1)
        taskDb.createAll(listOf(taskA, taskB))
        taskDb.updateStatus("idem2-a", "failed")

        // 第一次：B 被取消
        val first = taskDb.cancelBlockedTasks("wr-1")
        assertEquals(1, first.size, "第一次应取消 1 个任务")

        // 第二次：B 已是 cancelled，不应重复处理
        val second = taskDb.cancelBlockedTasks("wr-1")
        assertTrue(second.isEmpty(), "第二次调用应返回空列表（幂等）")

        // 第三次：同上
        val third = taskDb.cancelBlockedTasks("wr-1")
        assertTrue(third.isEmpty(), "第三次调用应返回空列表（幂等）")
    }

    // ── 测试 17：listByType — 按任务类型查询 ─────────────────────────────────

    /**
     * 证明目的：listByType() 只返回指定 type 的任务，不同 type 互不干扰。
     */
    @Test
    fun testListByType() = runBlocking {
        taskDb.createAll(listOf(
            Task(id = "type-a1", type = "DOWNLOAD_TORCH", workflowRunId = "wr-1", materialId = "material-1", createdAt = nowSec()),
            Task(id = "type-a2", type = "DOWNLOAD_TORCH", workflowRunId = "wr-1", materialId = "material-1", createdAt = nowSec() + 1),
            Task(id = "type-b1", type = "DOWNLOAD_VIDEO", workflowRunId = "wr-1", materialId = "material-1", createdAt = nowSec() + 2),
        ))

        val torchTasks = taskDb.listByType("DOWNLOAD_TORCH")
        assertEquals(2, torchTasks.size, "应返回 2 个 DOWNLOAD_TORCH 任务")
        assertTrue(torchTasks.all { it.type == "DOWNLOAD_TORCH" }, "所有任务 type 必须是 DOWNLOAD_TORCH")

        val videoTasks = taskDb.listByType("DOWNLOAD_VIDEO")
        assertEquals(1, videoTasks.size, "应返回 1 个 DOWNLOAD_VIDEO 任务")
        assertEquals("type-b1", videoTasks.first().id)

        val emptyTasks = taskDb.listByType("NONEXISTENT")
        assertTrue(emptyTasks.isEmpty(), "不存在的 type 应返回空列表")
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────────────

    private fun nowSec() = System.currentTimeMillis() / 1000L
}
