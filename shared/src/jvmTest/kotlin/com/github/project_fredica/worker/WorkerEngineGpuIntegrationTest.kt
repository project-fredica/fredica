package com.github.project_fredica.worker

// =============================================================================
// WorkerEngineGpuIntegrationTest —— WorkerEngine × GpuResourceLock 联合集成测试
// =============================================================================
//
// 被测对象：WorkerEngine（轮询调度 + Semaphore 并发限制）
//           × GpuResourceLock（GPU 资源优先级锁）
//
// 测试目的：验证两个子系统联合工作时的端到端行为：
//   - WorkerEngine 按 priority DESC 认领任务
//   - GPU 任务经过 GpuResourceLock 串行化
//   - 非 GPU 任务不受 GpuResourceLock 限制，可与 GPU 任务并发
//   - 高优先级 GPU 任务在 GpuResourceLock 等待队列中优先获取锁
//
// 测试策略：
//   - FakeGpuExecutor：execute() 内调用 GpuResourceLock.withGpuLock()，模拟真实 GPU Executor
//   - FakeCpuExecutor：execute() 内不使用 GpuResourceLock，模拟 CPU 任务
//   - 通过 AtomicInteger / CompletableDeferred 精确观察并发行为和执行顺序
//   - WorkerEngine 是全局单例，每个测试通过独立 scope + DB 隔离
//
// 测试矩阵：
//   1. testGpuTasksSerializedByLock       — GPU 任务经 WorkerEngine 调度后被 GpuResourceLock 串行化
//   2. testCpuTasksNotBlockedByGpuLock    — 非 GPU 任务不受 GPU 锁限制，可与 GPU 任务并发
//   3. testGpuPriorityPreemptionViaEngine — 高优先级 GPU 任务在 GpuResourceLock 队列中优先获取锁
//   4. testMixedWorkload_GpuSerialCpuParallel — 混合负载：GPU 串行 + CPU 并行
//   5. testFullPriorityChain              — 完整优先级链：claimNext 排序 → Semaphore → GpuResourceLock 排序
//
// 测试环境：每个测试用例独立的 SQLite 临时文件（@BeforeTest 重新创建）。
// =============================================================================

import com.github.project_fredica.db.RestartTaskLogDb
import com.github.project_fredica.db.RestartTaskLogService
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskDb
import com.github.project_fredica.db.TaskPriority
import com.github.project_fredica.db.TaskService
import com.github.project_fredica.db.WorkflowRun
import com.github.project_fredica.db.WorkflowRunDb
import com.github.project_fredica.db.WorkflowRunService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ktorm.database.Database
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkerEngineGpuIntegrationTest {

    private lateinit var db: Database
    private lateinit var taskDb: TaskDb
    private lateinit var workflowRunDb: WorkflowRunDb

    private val activeScopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun teardown() = runBlocking {
        activeScopes.forEach { scope ->
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
        activeScopes.clear()
    }

    @BeforeTest
    fun setup() = runBlocking {
        val tmpFile = File.createTempFile("gpu_integ_", ".db").also { it.deleteOnExit() }
        db = Database.connect(
            url    = "jdbc:sqlite:${tmpFile.absolutePath}",
            driver = "org.sqlite.JDBC",
        )
        workflowRunDb = WorkflowRunDb(db)
        taskDb = TaskDb(db)
        val restartTaskLogDb = RestartTaskLogDb(db)
        workflowRunDb.initialize()
        taskDb.initialize()
        restartTaskLogDb.initialize()
        TaskService.initialize(taskDb)
        WorkflowRunService.initialize(workflowRunDb)
        RestartTaskLogService.initialize(restartTaskLogDb)
        GpuResourceLock.resetForTest()
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────────────

    private fun nowSec() = System.currentTimeMillis() / 1000L

    private suspend fun createWorkflowRun(id: String, total: Int = 1) {
        workflowRunDb.create(
            WorkflowRun(
                id = id, materialId = "mat-1", template = "T",
                status = "pending", totalTasks = total, doneTasks = 0,
                createdAt = nowSec(),
            )
        )
    }

    private suspend fun createTask(
        id: String,
        workflowRunId: String,
        type: String = "FAKE_GPU",
        priority: Int = TaskPriority.DEV_TEST_DEFAULT,
    ) {
        taskDb.create(
            Task(
                id = id, type = type, workflowRunId = workflowRunId,
                materialId = "mat-1", priority = priority,
                createdAt = nowSec(),
            )
        )
    }

    private suspend fun waitUntil(timeoutMs: Long = 15_000L, predicate: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!predicate()) delay(200)
        }
    }

    private fun engineScope(): CoroutineScope =
        CoroutineScope(Dispatchers.IO + Job()).also { activeScopes.add(it) }

    // ── 测试 1：GPU 任务经 WorkerEngine 调度后被 GpuResourceLock 串行化 ──────

    /**
     * 证明目的：当多个 GPU 任务通过 WorkerEngine 调度时，
     *           GpuResourceLock 保证同一时刻最多 1 个 GPU 任务在执行。
     *
     * 证明过程：
     *   1. 启动引擎（maxWorkers=4），注册 FakeGpuExecutor（每个任务持锁 300ms）。
     *   2. 创建 3 个 GPU 任务。
     *   3. 用 AtomicInteger 记录 GPU 锁内的并发数和历史最大并发数。
     *   4. 等待全部任务完成。
     *   5. 断言 maxGpuConcurrent <= 1。
     *
     * 关键点：maxWorkers=4 允许 WorkerEngine 同时 dispatch 多个任务，
     * 但 GpuResourceLock 将 GPU 任务的实际执行串行化。
     */
    @Test
    fun testGpuTasksSerializedByLock() = runBlocking {
        val gpuRunning = AtomicInteger(0)
        val maxGpuConcurrent = AtomicInteger(0)

        val gpuExecutor = object : TaskExecutor {
            override val taskType = "FAKE_GPU"
            override suspend fun execute(task: Task): ExecuteResult {
                return GpuResourceLock.withGpuLock(task.id, task.priority) {
                    val current = gpuRunning.incrementAndGet()
                    maxGpuConcurrent.updateAndGet { maxOf(it, current) }
                    delay(300)
                    gpuRunning.decrementAndGet()
                    ExecuteResult(result = """{"gpu":"ok"}""")
                }
            }
        }

        WorkerEngine.start(maxWorkers = 4, scope = engineScope(), executors = listOf(gpuExecutor))

        createWorkflowRun("wf-gpu-serial", total = 3)
        createTask("t-gpu-1", "wf-gpu-serial", priority = 6)
        createTask("t-gpu-2", "wf-gpu-serial", priority = 6)
        createTask("t-gpu-3", "wf-gpu-serial", priority = 6)

        waitUntil(20_000) {
            taskDb.listByWorkflowRun("wf-gpu-serial").all { it.status == "completed" }
        }

        assertEquals(3, taskDb.listByWorkflowRun("wf-gpu-serial").count { it.status == "completed" },
            "3 个 GPU 任务都应完成")
        assertTrue(
            maxGpuConcurrent.get() <= 1,
            "GpuResourceLock 应将 GPU 任务并发数限制在 1（实测最大值=${maxGpuConcurrent.get()}）"
        )
    }

    // ── 测试 2：非 GPU 任务不受 GPU 锁限制 ──────────────────────────────────────

    /**
     * 证明目的：CPU 任务不调用 GpuResourceLock，因此不受 GPU 锁限制，
     *           可以与 GPU 任务并发执行。
     *
     * 证明过程：
     *   1. 启动引擎（maxWorkers=4），注册 FakeGpuExecutor 和 FakeCpuExecutor。
     *   2. 创建 1 个 GPU 任务（持锁 5000ms）和 2 个 CPU 任务（各 100ms）。
     *   3. GPU 任务持锁期间，CPU 任务应能并发执行并先完成。
     *   4. 用 CompletableDeferred 记录 CPU 任务完成时 GPU 任务是否仍在执行。
     *
     * 注意：WorkerEngine POLL_INTERVAL_MS=1s，每轮认领 1 个任务。
     * GPU 任务（priority=10）先被认领，CPU 任务在后续轮询周期被认领。
     * GPU 任务需要足够长（5s），确保 CPU 任务被认领并执行完毕时 GPU 仍在运行。
     */
    @Test
    fun testCpuTasksNotBlockedByGpuLock() = runBlocking {
        val gpuStarted = CompletableDeferred<Unit>()
        val cpuCompleted = AtomicInteger(0)
        val cpuCompletedWhileGpuRunning = AtomicInteger(0)
        val gpuFinished = CompletableDeferred<Unit>()

        val gpuExecutor = object : TaskExecutor {
            override val taskType = "FAKE_GPU"
            override suspend fun execute(task: Task): ExecuteResult {
                return GpuResourceLock.withGpuLock(task.id, task.priority) {
                    gpuStarted.complete(Unit)
                    // GPU 任务持锁 5s，确保 CPU 任务在后续轮询周期被认领并执行时
                    // GPU 任务仍在运行（WorkerEngine POLL_INTERVAL_MS=1s，CPU 任务约 2-3s 后才被认领）
                    delay(5000)
                    gpuFinished.complete(Unit)
                    ExecuteResult(result = """{"gpu":"ok"}""")
                }
            }
        }

        val cpuExecutor = object : TaskExecutor {
            override val taskType = "FAKE_CPU"
            override suspend fun execute(task: Task): ExecuteResult {
                delay(100)
                cpuCompleted.incrementAndGet()
                if (!gpuFinished.isCompleted) {
                    cpuCompletedWhileGpuRunning.incrementAndGet()
                }
                return ExecuteResult(result = """{"cpu":"ok"}""")
            }
        }

        WorkerEngine.start(maxWorkers = 4, scope = engineScope(), executors = listOf(gpuExecutor, cpuExecutor))

        createWorkflowRun("wf-mixed-1", total = 3)
        // GPU 任务高优先级，先被认领
        createTask("t-gpu-block", "wf-mixed-1", type = "FAKE_GPU", priority = 10)
        createTask("t-cpu-1", "wf-mixed-1", type = "FAKE_CPU", priority = 5)
        createTask("t-cpu-2", "wf-mixed-1", type = "FAKE_CPU", priority = 5)

        waitUntil(30_000) {
            taskDb.listByWorkflowRun("wf-mixed-1").all { it.status == "completed" }
        }

        assertEquals(3, taskDb.listByWorkflowRun("wf-mixed-1").count { it.status == "completed" },
            "所有任务都应完成")
        assertTrue(
            cpuCompletedWhileGpuRunning.get() > 0,
            "至少 1 个 CPU 任务应在 GPU 任务执行期间完成（实际=${cpuCompletedWhileGpuRunning.get()}），" +
            "证明 CPU 任务不受 GpuResourceLock 阻塞"
        )
    }

    // ── 测试 3：高优先级 GPU 任务在 GpuResourceLock 队列中优先获取锁 ──────────

    /**
     * 证明目的：当多个 GPU 任务在 GpuResourceLock 等待队列中排队时，
     *           高优先级任务优先获取锁。
     *
     * 证明过程：
     *   1. 启动引擎（maxWorkers=8），注册 FakeGpuExecutor。
     *   2. 创建 1 个低优先级 GPU 任务（先被认领，持锁阻塞）。
     *   3. 创建 3 个不同优先级的 GPU 任务（在锁等待队列中排队）。
     *   4. 释放第一个任务的锁后，记录后续任务获取锁的顺序。
     *   5. 断言获取顺序为 priority DESC。
     *
     * 注意：WorkerEngine 的 claimNext 按 priority DESC 认领，
     * 但由于 POLL_INTERVAL_MS=1s，多个任务可能在不同轮次被认领。
     * 本测试通过 CompletableDeferred 控制第一个任务的持锁时间，
     * 确保后续任务都已进入 GpuResourceLock 等待队列后再释放。
     */
    @Test
    fun testGpuPriorityPreemptionViaEngine() = runBlocking {
        val holdLock = CompletableDeferred<Unit>()
        val acquireOrder = mutableListOf<String>()
        val orderMutex = kotlinx.coroutines.sync.Mutex()
        val allWaitersQueued = CompletableDeferred<Unit>()
        val waitersCount = AtomicInteger(0)

        val gpuExecutor = object : TaskExecutor {
            override val taskType = "FAKE_GPU"
            override suspend fun execute(task: Task): ExecuteResult {
                return GpuResourceLock.withGpuLock(task.id, task.priority) {
                    if (task.id == "t-holder") {
                        // 持锁者：等待所有等待者入队后再释放
                        holdLock.await()
                    } else {
                        // 等待者：记录获取锁的顺序
                        orderMutex.lock()
                        acquireOrder.add(task.id)
                        orderMutex.unlock()
                    }
                    ExecuteResult(result = """{"gpu":"ok"}""")
                }
            }
        }

        // 用一个 CPU executor 来检测等待者入队
        // 实际上我们通过轮询 statusText 来判断等待者是否入队
        WorkerEngine.start(maxWorkers = 8, scope = engineScope(), executors = listOf(gpuExecutor))

        createWorkflowRun("wf-gpu-prio", total = 4)
        // 持锁者：最高优先级，确保第一个被认领
        createTask("t-holder", "wf-gpu-prio", priority = 20)

        // 等待持锁者获取锁
        waitUntil {
            taskDb.findById("t-holder")?.status == "running"
        }

        // 创建 3 个不同优先级的等待者（间隔创建确保 created_at 不同）
        createTask("t-low", "wf-gpu-prio", priority = 2)
        delay(50)
        createTask("t-high", "wf-gpu-prio", priority = 8)
        delay(50)
        createTask("t-mid", "wf-gpu-prio", priority = 5)

        // 等待所有 3 个等待者进入 GpuResourceLock 等待队列（statusText = "等待 GPU 资源…"）
        waitUntil(20_000) {
            val tasks = listOf("t-low", "t-high", "t-mid")
            tasks.all { id ->
                taskDb.findById(id)?.statusText == "等待 GPU 资源…"
            }
        }

        // 释放持锁者
        holdLock.complete(Unit)

        // 等待所有任务完成
        waitUntil(20_000) {
            taskDb.listByWorkflowRun("wf-gpu-prio").all { it.status == "completed" }
        }

        assertEquals(
            listOf("t-high", "t-mid", "t-low"), acquireOrder,
            "GpuResourceLock 应按优先级降序释放等待者：8 → 5 → 2（实际顺序=$acquireOrder）"
        )
    }

    // ── 测试 4：混合负载 — GPU 串行 + CPU 并行 ──────────────────────────────────

    /**
     * 证明目的：在混合负载下，GPU 任务被 GpuResourceLock 串行化，
     *           而 CPU 任务可以并行执行，两者互不干扰。
     *
     * 证明过程：
     *   1. 启动引擎（maxWorkers=8），注册 FakeGpuExecutor 和 FakeCpuExecutor。
     *   2. 创建 3 个 GPU 任务（各 200ms）和 4 个 CPU 任务（各 2500ms）。
     *   3. 用 AtomicInteger 分别记录 GPU 和 CPU 的最大并发数。
     *   4. 断言 GPU 最大并发 <= 1，CPU 最大并发 >= 2。
     *
     * 注意：WorkerEngine POLL_INTERVAL_MS=1s，每轮认领 1 个任务。
     * CPU 任务需要足够长（2500ms > 1s 轮询间隔），使得多个 CPU 任务
     * 在被依次认领后能够重叠执行，从而观察到并发数 >= 2。
     */
    @Test
    fun testMixedWorkload_GpuSerialCpuParallel() = runBlocking {
        val gpuRunning = AtomicInteger(0)
        val maxGpuConcurrent = AtomicInteger(0)
        val cpuRunning = AtomicInteger(0)
        val maxCpuConcurrent = AtomicInteger(0)

        val gpuExecutor = object : TaskExecutor {
            override val taskType = "FAKE_GPU"
            override suspend fun execute(task: Task): ExecuteResult {
                return GpuResourceLock.withGpuLock(task.id, task.priority) {
                    val current = gpuRunning.incrementAndGet()
                    maxGpuConcurrent.updateAndGet { maxOf(it, current) }
                    delay(200)
                    gpuRunning.decrementAndGet()
                    ExecuteResult(result = """{"gpu":"ok"}""")
                }
            }
        }

        val cpuExecutor = object : TaskExecutor {
            override val taskType = "FAKE_CPU"
            override suspend fun execute(task: Task): ExecuteResult {
                val current = cpuRunning.incrementAndGet()
                maxCpuConcurrent.updateAndGet { maxOf(it, current) }
                // CPU 任务持续 2500ms（> POLL_INTERVAL_MS=1s），确保多个 CPU 任务
                // 在被依次认领后能够重叠执行
                delay(2500)
                cpuRunning.decrementAndGet()
                return ExecuteResult(result = """{"cpu":"ok"}""")
            }
        }

        WorkerEngine.start(maxWorkers = 8, scope = engineScope(), executors = listOf(gpuExecutor, cpuExecutor))

        createWorkflowRun("wf-mixed-2", total = 7)
        // GPU 任务（高优先级，先被认领）
        createTask("t-gpu-a", "wf-mixed-2", type = "FAKE_GPU", priority = 14)
        createTask("t-gpu-b", "wf-mixed-2", type = "FAKE_GPU", priority = 14)
        createTask("t-gpu-c", "wf-mixed-2", type = "FAKE_GPU", priority = 14)
        // CPU 任务（低优先级，后被认领，但不受 GPU 锁限制）
        createTask("t-cpu-a", "wf-mixed-2", type = "FAKE_CPU", priority = 5)
        createTask("t-cpu-b", "wf-mixed-2", type = "FAKE_CPU", priority = 5)
        createTask("t-cpu-c", "wf-mixed-2", type = "FAKE_CPU", priority = 5)
        createTask("t-cpu-d", "wf-mixed-2", type = "FAKE_CPU", priority = 5)

        waitUntil(45_000) {
            taskDb.listByWorkflowRun("wf-mixed-2").all { it.status == "completed" }
        }

        assertEquals(7, taskDb.listByWorkflowRun("wf-mixed-2").count { it.status == "completed" },
            "所有 7 个任务都应完成")
        assertTrue(
            maxGpuConcurrent.get() <= 1,
            "GPU 任务应被 GpuResourceLock 串行化（实测最大并发=${maxGpuConcurrent.get()}）"
        )
        assertTrue(
            maxCpuConcurrent.get() >= 2,
            "CPU 任务应能并行执行（实测最大并发=${maxCpuConcurrent.get()}）"
        )
    }

    // ── 测试 5：完整优先级链 ─────────────────────────────────────────────────────

    /**
     * 证明目的：验证完整的优先级传播链：
     *   claimNext() 按 priority DESC 认领 → WorkerEngine dispatch →
     *   GpuResourceLock 按 priority DESC 释放等待者。
     *
     * 证明过程：
     *   1. 启动引擎（maxWorkers=8），注册 FakeGpuExecutor。
     *   2. 创建 1 个持锁任务（最高优先级 20，先被认领并持锁）。
     *   3. 创建 5 个不同优先级的 GPU 任务。
     *   4. 等待所有 5 个任务进入 GpuResourceLock 等待队列。
     *   5. 释放持锁者，记录后续任务获取锁的顺序。
     *   6. 断言顺序严格按 priority DESC。
     *
     * 这验证了两层优先级的协同：
     *   - claimNext SQL 的 ORDER BY priority DESC 决定认领顺序
     *   - GpuResourceLock 的 PriorityQueue 决定锁释放顺序
     *   两者一致，高优先级任务始终优先执行。
     */
    @Test
    fun testFullPriorityChain() = runBlocking {
        val holdLock = CompletableDeferred<Unit>()
        val acquireOrder = mutableListOf<String>()
        val orderMutex = kotlinx.coroutines.sync.Mutex()

        val gpuExecutor = object : TaskExecutor {
            override val taskType = "FAKE_GPU"
            override suspend fun execute(task: Task): ExecuteResult {
                return GpuResourceLock.withGpuLock(task.id, task.priority) {
                    if (task.id == "t-chain-holder") {
                        holdLock.await()
                    } else {
                        orderMutex.lock()
                        acquireOrder.add(task.id)
                        orderMutex.unlock()
                    }
                    ExecuteResult(result = """{"gpu":"ok"}""")
                }
            }
        }

        WorkerEngine.start(maxWorkers = 8, scope = engineScope(), executors = listOf(gpuExecutor))

        createWorkflowRun("wf-chain", total = 6)
        // 持锁者
        createTask("t-chain-holder", "wf-chain", priority = 20)

        // 等待持锁者获取锁
        waitUntil {
            taskDb.findById("t-chain-holder")?.status == "running"
        }

        // 创建 5 个不同优先级的任务
        createTask("t-chain-p1", "wf-chain", priority = 1)
        delay(50)
        createTask("t-chain-p9", "wf-chain", priority = 9)
        delay(50)
        createTask("t-chain-p3", "wf-chain", priority = 3)
        delay(50)
        createTask("t-chain-p7", "wf-chain", priority = 7)
        delay(50)
        createTask("t-chain-p5", "wf-chain", priority = 5)

        // 等待所有 5 个任务进入 GpuResourceLock 等待队列
        waitUntil(25_000) {
            val ids = listOf("t-chain-p1", "t-chain-p9", "t-chain-p3", "t-chain-p7", "t-chain-p5")
            ids.all { id ->
                taskDb.findById(id)?.statusText == "等待 GPU 资源…"
            }
        }

        // 释放持锁者
        holdLock.complete(Unit)

        // 等待所有任务完成
        waitUntil(25_000) {
            taskDb.listByWorkflowRun("wf-chain").all { it.status == "completed" }
        }

        assertEquals(
            listOf("t-chain-p9", "t-chain-p7", "t-chain-p5", "t-chain-p3", "t-chain-p1"),
            acquireOrder,
            "完整优先级链应按 priority DESC 执行：9→7→5→3→1（实际顺序=$acquireOrder）"
        )
    }
}
