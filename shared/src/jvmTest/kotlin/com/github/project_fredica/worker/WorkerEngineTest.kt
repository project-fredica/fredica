package com.github.project_fredica.worker

// =============================================================================
// WorkerEngineTest —— WorkerEngine 集成测试
// =============================================================================
//
// 被测对象：WorkerEngine（轮询调度 + Semaphore 并发限制 + 重试逻辑）
//
// 测试策略：
//   - 使用 FakeExecutor（可控的假执行器）替代真实 Executor，避免依赖 ffmpeg/Python。
//   - WorkerEngine 是全局单例，每个测试通过独立的 scope + taskDb + pipelineDb 隔离。
//   - 测试属于集成测试：WorkerEngine + TaskDb + PipelineDb 联动运行，
//     通过轮询数据库状态来断言最终结果（"最终一致"断言模式）。
//   - waitUntil() 以最长超时（10~20s）等待异步状态变化，避免硬 sleep。
//
// 测试矩阵：
//   1. testSuccessFlow        — 正常执行路径：任务完成，pipeline 联动更新
//   2. testRetryOnFailure     — 失败后重试：retry_count 递增，最终成功
//   3. testMaxRetriesExhausted— 重试耗尽：任务永久失败，pipeline 联动失败
//   4. testPriorityOrder      — 优先级调度：高优先级任务先被认领
//   5. testConcurrencyLimit   — 并发上限：Semaphore 限制同时执行数量
//
// 测试环境：每个测试用例独立的 SQLite 临时文件（@BeforeTest 重新创建）。
//
// 注意事项：
//   WorkerEngine.start() 每次调用都会在传入的 scope 内启动新的轮询协程。
//   测试结束后 scope 被 GC 回收，协程自然停止。多次调用 start() 会叠加 registry，
//   但由于每个测试用的是独立临时库和新的 TaskService/PipelineService 实例，
//   旧协程读到的依然是当前测试的数据，不会互相干扰。
// =============================================================================

import com.github.project_fredica.db.PipelineDb
import com.github.project_fredica.db.PipelineInstance
import com.github.project_fredica.db.PipelineService
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskDb
import com.github.project_fredica.db.TaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

class WorkerEngineTest {

    private lateinit var db: Database
    private lateinit var taskDb: TaskDb
    private lateinit var pipelineDb: PipelineDb

    // 记录本轮测试启动的所有引擎 scope，在 @AfterTest 中统一取消。
    // 目的：WorkerEngine 是全局单例，每次 start() 都会在 scope 内新增轮询协程。
    // 若不取消，上一个测试的协程在 @BeforeTest 重置 TaskService/PipelineService 后，
    // 仍会访问新数据库并抢先认领任务——导致并发数超出当前测试的 maxWorkers 限制。
    private val activeScopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun teardown() {
        // 取消所有本轮测试的引擎协程，确保不干扰下一个测试
        activeScopes.forEach { it.cancel() }
        activeScopes.clear()
    }

    /**
     * 每个测试前重建临时文件数据库并重新初始化所有服务。
     *
     * 为什么用临时文件而非 :memory:：
     *   ktorm 连接池每次 useConnection{} 都可能打开新连接，
     *   :memory: 库每条连接拥有独立空数据集（表不共享）。
     *   临时文件让所有连接看到同一份物理数据。
     */
    @BeforeTest
    fun setup() = runBlocking {
        val tmpFile = File.createTempFile("workertest_", ".db").also { it.deleteOnExit() }
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

    // ── 辅助方法 ──────────────────────────────────────────────────────────────

    private fun nowSec() = System.currentTimeMillis() / 1000L

    private suspend fun createPipeline(id: String, total: Int = 1) {
        pipelineDb.create(
            PipelineInstance(
                id = id, materialId = "mat-1", template = "T",
                status = "pending", totalTasks = total, doneTasks = 0,
                createdAt = nowSec(),
            )
        )
    }

    private suspend fun createTask(
        id: String,
        pipelineId: String,
        priority: Int = 0,
        dependsOn: String = "[]",
        maxRetries: Int = 3,
    ) {
        taskDb.create(
            Task(
                id = id, type = "FAKE", pipelineId = pipelineId,
                materialId = "mat-1", priority = priority,
                dependsOn = dependsOn, createdAt = nowSec(),
                maxRetries = maxRetries,
            )
        )
    }

    /**
     * 轮询等待直到 [predicate] 返回 true，超时后抛出 [kotlinx.coroutines.TimeoutCancellationException]。
     * 每 200ms 检查一次，避免空转浪费 CPU。
     */
    private suspend fun waitUntil(timeoutMs: Long = 10_000L, predicate: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!predicate()) delay(200)
        }
    }

    /**
     * 创建一个带 Job 的引擎 scope，并记录到 [activeScopes]。
     * @AfterTest 时调用 cancel() 停止所有轮询协程，防止跨测试干扰。
     */
    private fun engineScope(): CoroutineScope =
        CoroutineScope(Dispatchers.IO + Job()).also { activeScopes.add(it) }

    // ── 测试 1：正常执行路径 ──────────────────────────────────────────────────

    /**
     * 证明目的：当 Executor 执行成功时，任务状态变为 completed，
     *           且 pipeline 的状态也联动变为 completed。
     *
     * 证明过程：
     *   1. 启动引擎，使用 FakeExecutor（50ms 后返回成功）。
     *   2. 创建 1 个任务的流水线，并插入任务。
     *   3. 等待（最多 10s）任务状态变为 "completed"。
     *   4. 等待（最多 10s）pipeline 状态变为 "completed"。
     *   5. 最终断言两者状态均为 "completed"。
     *
     * 这验证了完整的数据流：
     *   claimNext() → status=claimed → execute() → updateStatus(completed)
     *   → recalculate() → pipeline.status=completed
     */
    @Test
    fun testSuccessFlow() = runBlocking {
        WorkerEngine.start(
            maxWorkers = 2,
            scope = engineScope(),
            executors = listOf(FakeExecutor(succeedAfterMs = 50)),
        )

        createPipeline("pl-success", total = 1)
        createTask("t-success", "pl-success")

        waitUntil { taskDb.listByPipeline("pl-success").first().status == "completed" }
        waitUntil { pipelineDb.getById("pl-success")?.status == "completed" }

        assertEquals("completed", taskDb.listByPipeline("pl-success").first().status,
            "任务应已完成")
        assertEquals("completed", pipelineDb.getById("pl-success")?.status,
            "pipeline 应联动变为 completed")
    }

    // ── 测试 2：失败后重试，最终成功 ─────────────────────────────────────────

    /**
     * 证明目的：任务首次执行失败时，WorkerEngine 会将 retry_count +1 并重置为 pending，
     *           下次轮询时重新认领并再次执行，最终成功后状态变为 completed。
     *
     * 证明过程：
     *   1. 使用 FakeExecutor(failTimes=1)：前 1 次调用返回失败，第 2 次返回成功。
     *   2. 等待（最多 12s）任务状态变为 "completed"。
     *      （需要比单次超时更长，因为要经历：失败 → 重置 pending → 再认领 → 成功）
     *   3. 断言最终状态为 "completed"，且 retryCount >= 1。
     *
     * 验证的重试逻辑：
     *   - WorkerEngine 在执行失败且 retryCount < maxRetries 时：
     *     调用 incrementRetry()（retryCount+1）→ updateStatus("pending")
     *   - 任务回到 pending 后，下一轮 claimNext() 可再次认领它。
     *   - retryCount 字段从数据库中读回，能反映累计重试次数。
     */
    @Test
    fun testRetryOnFailure() = runBlocking {
        WorkerEngine.start(
            maxWorkers = 2,
            scope = engineScope(),
            executors = listOf(FakeExecutor(failTimes = 1, succeedAfterMs = 30)),
        )

        createPipeline("pl-retry", total = 1)
        createTask("t-retry", "pl-retry")

        waitUntil(12_000) {
            taskDb.listByPipeline("pl-retry").first().status == "completed"
        }

        val task = taskDb.listByPipeline("pl-retry").first()
        assertEquals("completed", task.status, "经过重试后任务最终应成功")
        assertTrue(task.retryCount >= 1, "retryCount 应记录至少 1 次重试")
    }

    // ── 测试 3：重试次数耗尽后永久失败 ───────────────────────────────────────

    /**
     * 证明目的：当 retryCount 达到 maxRetries 后，任务不再重试，
     *           永久保持 failed 状态，且 pipeline 联动变为 failed。
     *
     * 证明过程：
     *   1. 使用 FakeExecutor(failTimes=Int.MAX_VALUE)：永远返回失败。
     *   2. 创建任务时设置 maxRetries=2（最多允许 2 次重试，即总共执行 3 次）。
     *   3. 等待（最多 20s）任务状态变为 "failed"。
     *      （需要等待：失败→重试→失败→重试→失败→永久失败，约 3 轮执行 + 退避时间）
     *   4. 断言任务 status = "failed"。
     *   5. 等待并断言 pipeline status = "failed"（recalculate 联动）。
     *
     * 验证的终止条件：
     *   WorkerEngine 中的判断：if (task.retryCount >= task.maxRetries) → finishFailed()
     *   finishFailed() 调用 updateStatus("failed")，不再重置为 pending。
     */
    @Test
    fun testMaxRetriesExhausted() = runBlocking {
        WorkerEngine.start(
            maxWorkers = 2,
            scope = engineScope(),
            executors = listOf(FakeExecutor(failTimes = Int.MAX_VALUE)),
        )

        createPipeline("pl-exhaust", total = 1)
        createTask("t-exhaust", "pl-exhaust", maxRetries = 2)

        waitUntil(20_000) {
            taskDb.listByPipeline("pl-exhaust").first().status == "failed"
        }

        assertEquals("failed", taskDb.listByPipeline("pl-exhaust").first().status,
            "重试次数耗尽后任务应永久失败")
        waitUntil { pipelineDb.getById("pl-exhaust")?.status == "failed" }
        assertEquals("failed", pipelineDb.getById("pl-exhaust")?.status,
            "pipeline 应联动变为 failed")
    }

    // ── 测试 4：优先级调度顺序 ────────────────────────────────────────────────

    /**
     * 证明目的：当队列中同时存在多个任务时，priority 值大的任务优先被认领执行。
     *
     * 证明过程：
     *   1. 使用自定义 Executor（trackingExec），在 execute() 中记录任务被执行的顺序。
     *   2. 设置 maxWorkers=1（强制串行执行），确保任务一个接一个被处理。
     *      若 maxWorkers>1，多个任务可能并发执行，执行顺序不确定。
     *   3. 同时向队列插入 3 个任务：t-low(p=3) / t-high(p=8) / t-mid(p=5)。
     *   4. 等待全部任务完成。
     *   5. 断言 claimOrder 的第一个元素是 "t-high"。
     *
     * 验证的 SQL 排序：claimNext() 子查询使用 ORDER BY priority DESC, created_at ASC，
     * priority=8 的 t-high 排在最前，因此总是被第一个认领。
     */
    @Test
    fun testPriorityOrder() = runBlocking {
        val claimOrder = mutableListOf<String>()

        // 自定义 Executor：execute 时先记录任务 ID，再等 30ms 模拟工作
        val trackingExec = object : TaskExecutor {
            override val taskType = "FAKE"
            override suspend fun execute(task: Task): ExecuteResult {
                synchronized(claimOrder) { claimOrder.add(task.id) }
                delay(30)
                return ExecuteResult()
            }
        }

        // maxWorkers=1 确保任务串行执行，执行顺序 = 认领顺序
        WorkerEngine.start(maxWorkers = 1, scope = engineScope(), executors = listOf(trackingExec))

        createPipeline("pl-priority", total = 3)
        createTask("t-low",  "pl-priority", priority = 3)
        createTask("t-high", "pl-priority", priority = 8)
        createTask("t-mid",  "pl-priority", priority = 5)

        waitUntil(15_000) {
            taskDb.listByPipeline("pl-priority").all { it.status == "completed" }
        }

        assertEquals("t-high", claimOrder.firstOrNull(),
            "priority=8 的 t-high 应该是第一个被认领的任务")
    }

    // ── 测试 5：Semaphore 并发上限 ────────────────────────────────────────────

    /**
     * 证明目的：WorkerEngine 通过 Semaphore(maxWorkers) 严格限制同时执行的任务数量，
     *           超出上限的任务必须等待，不会并发溢出。
     *
     * 证明过程：
     *   1. 使用自定义 Executor（monitorExec），在 execute() 中用 AtomicInteger 统计：
     *      - runningCount：进入 execute 时 +1，退出时 -1（当前并发数）
     *      - maxObserved：记录运行期间观察到的最大并发数（滑动最大值）
     *   2. 设置 maxWorkers=2，向队列投入 5 个任务。
     *   3. 每个任务执行耗时 400ms（足够长，保证重叠执行窗口）。
     *   4. 等待全部任务完成。
     *   5. 断言 maxObserved <= 2。
     *
     * 为什么 400ms 足够：
     *   若无 Semaphore 限制，5 个任务会几乎同时启动，maxObserved 接近 5。
     *   有 Semaphore(2) 限制，最多 2 个任务同时执行，maxObserved 必须 <= 2。
     *   400ms 执行窗口远大于轮询间隔（1s）的残差，足以制造并发重叠场景。
     */
    @Test
    fun testConcurrencyLimit() = runBlocking {
        val runningCount = AtomicInteger(0)  // 当前并发执行数
        val maxObserved  = AtomicInteger(0)  // 历史最大并发数

        val monitorExec = object : TaskExecutor {
            override val taskType = "FAKE"
            override suspend fun execute(task: Task): ExecuteResult {
                val current = runningCount.incrementAndGet()
                // 原子地更新最大值：如果 current 比历史最大值大，则替换
                maxObserved.updateAndGet { maxOf(it, current) }
                delay(400) // 保持"执行中"状态 400ms，制造并发重叠窗口
                runningCount.decrementAndGet()
                return ExecuteResult()
            }
        }

        WorkerEngine.start(maxWorkers = 2, scope = engineScope(), executors = listOf(monitorExec))

        createPipeline("pl-concurrency", total = 5)
        (1..5).forEach { i -> createTask("t-conc-$i", "pl-concurrency") }

        waitUntil(20_000) {
            taskDb.listByPipeline("pl-concurrency").all { it.status == "completed" }
        }

        assertTrue(
            maxObserved.get() <= 2,
            "Semaphore(2) 应将并发数严格限制在 2 以内（实测最大值=${maxObserved.get()}）"
        )
    }
}

// =============================================================================
// FakeExecutor —— 可控的测试替身（Test Double）
// =============================================================================
//
// 用途：替换真实的 TaskExecutor（如 DownloadVideoExecutor），
//       让测试不依赖 curl / ffmpeg / Python 等外部工具，
//       并能精确控制执行耗时和失败次数。
//
// 行为：
//   - 前 failTimes 次调用：等待 succeedAfterMs 后返回失败结果
//   - 之后的调用：等待 succeedAfterMs 后返回成功结果
//   - 使用 AtomicInteger 计数，线程安全
// =============================================================================

/**
 * @param failTimes       在返回成功之前，先失败多少次（0 = 直接成功）
 * @param succeedAfterMs  每次 execute() 调用的模拟耗时（毫秒）
 */
class FakeExecutor(
    private val failTimes: Int = 0,
    private val succeedAfterMs: Long = 10,
) : TaskExecutor {
    override val taskType = "FAKE"

    // AtomicInteger 保证多线程环境下计数准确（WorkerEngine 在 IO 线程池并发执行）
    private val callCount = AtomicInteger(0)

    override suspend fun execute(task: Task): ExecuteResult {
        delay(succeedAfterMs)
        return if (callCount.getAndIncrement() < failTimes) {
            // 前 failTimes 次返回失败
            ExecuteResult(error = "Simulated failure", errorType = "FAKE_ERROR")
        } else {
            // 之后返回成功
            ExecuteResult(result = """{"fake":"ok"}""")
        }
    }
}
