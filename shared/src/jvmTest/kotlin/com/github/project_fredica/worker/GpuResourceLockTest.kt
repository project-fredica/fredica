package com.github.project_fredica.worker

// =============================================================================
// GpuResourceLockTest —— GPU 资源互斥锁单元测试
// =============================================================================
//
// 被测对象：GpuResourceLock（Semaphore(1) 互斥 + statusText 等待提示）
//
// 测试策略：
//   - 直接测试 GpuResourceLock.withGpuLock()，不经过 WorkerEngine。
//   - 使用真实 TaskDb（SQLite 临时文件）验证 statusText 写入。
//   - 通过 AtomicInteger 观察并发数，证明同一时刻最多 1 个 GPU 任务在执行。
//   - 通过 CompletableDeferred 精确控制任务执行时序，避免依赖 sleep。
//
// 测试矩阵：
//   1. testMutualExclusion       — 两个 GPU 任务串行执行，并发数不超过 1
//   2. testWaitingStatusText     — 等待锁时 statusText 被设为"等待 GPU 资源…"
//   3. testStatusTextClearedOnAcquire — 获取锁后 statusText 被清除
//   4. testNoContentionNoStatusText   — 无竞争时不写入等待提示
//
// 测试环境：每个测试用例独立的 SQLite 临时文件（@BeforeTest 重新创建）。
// =============================================================================

import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskDb
import com.github.project_fredica.db.TaskService
import com.github.project_fredica.db.WorkflowRun
import com.github.project_fredica.db.WorkflowRunDb
import com.github.project_fredica.db.WorkflowRunService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ktorm.database.Database
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpuResourceLockTest {

    private lateinit var db: Database
    private lateinit var taskDb: TaskDb
    private lateinit var workflowRunDb: WorkflowRunDb

    private fun nowSec() = System.currentTimeMillis() / 1000L

    @BeforeTest
    fun setup() = runBlocking {
        val tmpFile = File.createTempFile("gpulocktest_", ".db").also { it.deleteOnExit() }
        db = Database.connect(
            url    = "jdbc:sqlite:${tmpFile.absolutePath}",
            driver = "org.sqlite.JDBC",
        )
        workflowRunDb = WorkflowRunDb(db)
        taskDb = TaskDb(db)
        workflowRunDb.initialize()
        taskDb.initialize()
        TaskService.initialize(taskDb)
        WorkflowRunService.initialize(workflowRunDb)
    }

    private suspend fun createWorkflowRun(id: String) {
        workflowRunDb.create(
            WorkflowRun(
                id = id, materialId = "mat-1", template = "T",
                status = "pending", totalTasks = 2, doneTasks = 0,
                createdAt = nowSec(),
            )
        )
    }

    private suspend fun createTask(id: String, workflowRunId: String) {
        taskDb.create(
            Task(
                id = id, type = "FAKE", workflowRunId = workflowRunId,
                materialId = "mat-1", createdAt = nowSec(),
            )
        )
    }

    /**
     * 轮询等待直到 [predicate] 返回 true。
     */
    private suspend fun waitUntil(timeoutMs: Long = 5_000L, predicate: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!predicate()) delay(50)
        }
    }

    // ── 测试 1：互斥性 ──────────────────────────────────────────────────────────

    /**
     * 证明目的：GpuResourceLock 保证同一时刻最多 1 个 GPU 任务在执行。
     *
     * 证明过程：
     *   1. 启动两个协程，各自调用 withGpuLock 执行一段耗时 300ms 的工作。
     *   2. 用 AtomicInteger 记录并发数和历史最大并发数。
     *   3. 等待两个协程都完成。
     *   4. 断言 maxObserved <= 1。
     */
    @Test
    fun testMutualExclusion() = runBlocking {
        createWorkflowRun("wf-mutex")
        createTask("t-mutex-1", "wf-mutex")
        createTask("t-mutex-2", "wf-mutex")

        val runningCount = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val completed = AtomicInteger(0)

        val job1 = launch(Dispatchers.IO) {
            GpuResourceLock.withGpuLock("t-mutex-1") {
                val current = runningCount.incrementAndGet()
                maxObserved.updateAndGet { maxOf(it, current) }
                delay(300)
                runningCount.decrementAndGet()
                completed.incrementAndGet()
            }
        }
        val job2 = launch(Dispatchers.IO) {
            GpuResourceLock.withGpuLock("t-mutex-2") {
                val current = runningCount.incrementAndGet()
                maxObserved.updateAndGet { maxOf(it, current) }
                delay(300)
                runningCount.decrementAndGet()
                completed.incrementAndGet()
            }
        }

        job1.join()
        job2.join()

        assertEquals(2, completed.get(), "两个任务都应完成")
        assertTrue(
            maxObserved.get() <= 1,
            "Semaphore(1) 应将 GPU 并发数严格限制在 1 以内（实测最大值=${maxObserved.get()}）"
        )
    }

    // ── 测试 2：等待时写入 statusText ────────────────────────────────────────────

    /**
     * 证明目的：当锁已被占用时，等待方的 statusText 被设为"等待 GPU 资源…"。
     *
     * 证明过程：
     *   1. 任务 A 先获取锁，用 CompletableDeferred 阻塞不释放。
     *   2. 任务 B 尝试获取锁，因锁被占用，应写入 statusText。
     *   3. 轮询数据库，断言任务 B 的 statusText = "等待 GPU 资源…"。
     *   4. 释放任务 A 的锁，等待任务 B 完成。
     */
    @Test
    fun testWaitingStatusText() = runBlocking {
        createWorkflowRun("wf-status")
        createTask("t-status-a", "wf-status")
        createTask("t-status-b", "wf-status")

        val holdLock = CompletableDeferred<Unit>()
        val taskBStartedWaiting = CompletableDeferred<Unit>()

        // 任务 A：获取锁后阻塞，直到 holdLock 被 complete
        val jobA = launch(Dispatchers.IO) {
            GpuResourceLock.withGpuLock("t-status-a") {
                holdLock.await()
            }
        }

        // 等待任务 A 确实获取了锁（给一点时间让协程调度）
        delay(100)

        // 任务 B：尝试获取锁，会被阻塞
        val jobB = launch(Dispatchers.IO) {
            GpuResourceLock.withGpuLock("t-status-b") {
                taskBStartedWaiting.complete(Unit)
            }
        }

        // 等待任务 B 的 statusText 被写入
        waitUntil {
            taskDb.findById("t-status-b")?.statusText == "等待 GPU 资源…"
        }

        val taskB = taskDb.findById("t-status-b")
        assertEquals("等待 GPU 资源…", taskB?.statusText,
            "等待 GPU 锁时应写入 statusText 提示")

        // 释放锁，让任务 B 完成
        holdLock.complete(Unit)
        jobA.join()
        jobB.join()
    }

    // ── 测试 3：获取锁后清除 statusText ──────────────────────────────────────────

    /**
     * 证明目的：任务获取到 GPU 锁后，之前写入的"等待 GPU 资源…"statusText 被清除。
     *
     * 证明过程：
     *   1. 任务 A 持锁阻塞，任务 B 等待（statusText 被写入）。
     *   2. 释放任务 A 的锁。
     *   3. 任务 B 获取锁后，在 block 内检查 statusText 已被清除。
     */
    @Test
    fun testStatusTextClearedOnAcquire() = runBlocking {
        createWorkflowRun("wf-clear")
        createTask("t-clear-a", "wf-clear")
        createTask("t-clear-b", "wf-clear")

        val holdLock = CompletableDeferred<Unit>()
        val taskBAcquired = CompletableDeferred<Unit>()
        val statusTextInsideBlock = CompletableDeferred<String?>()

        // 任务 A：持锁阻塞
        val jobA = launch(Dispatchers.IO) {
            GpuResourceLock.withGpuLock("t-clear-a") {
                holdLock.await()
            }
        }

        delay(100)

        // 任务 B：等待锁，获取后读取 statusText
        val jobB = launch(Dispatchers.IO) {
            GpuResourceLock.withGpuLock("t-clear-b") {
                taskBAcquired.complete(Unit)
                // 在 block 内读取 statusText，应已被 withGpuLock 清除
                val st = taskDb.findById("t-clear-b")?.statusText
                statusTextInsideBlock.complete(st)
            }
        }

        // 等待任务 B 确实在等待
        waitUntil {
            taskDb.findById("t-clear-b")?.statusText == "等待 GPU 资源…"
        }

        // 释放锁
        holdLock.complete(Unit)
        taskBAcquired.await()

        val st = statusTextInsideBlock.await()
        assertNull(st, "获取 GPU 锁后 statusText 应被清除为 null")

        jobA.join()
        jobB.join()
    }

    // ── 测试 4：无竞争时不写入 statusText ────────────────────────────────────────

    /**
     * 证明目的：当锁空闲时，直接获取，不写入"等待 GPU 资源…"。
     *
     * 证明过程：
     *   1. 确保没有其他任务持锁。
     *   2. 调用 withGpuLock，在 block 内读取 statusText。
     *   3. 断言 statusText 为 null（withGpuLock 进入时会清除，但不应先写入等待提示）。
     *
     * 注意：withGpuLock 在获取锁后会无条件调用 updateStatusText(null)，
     * 这是一个 no-op（null → null），不影响正确性。
     */
    @Test
    fun testNoContentionNoStatusText() = runBlocking {
        createWorkflowRun("wf-nocontention")
        createTask("t-nocontention", "wf-nocontention")

        // 记录 block 执行前 statusText 是否被写入过"等待 GPU 资源…"
        var sawWaitingStatus = false

        GpuResourceLock.withGpuLock("t-nocontention") {
            val st = taskDb.findById("t-nocontention")?.statusText
            if (st == "等待 GPU 资源…") sawWaitingStatus = true
        }

        assertTrue(!sawWaitingStatus, "无竞争时不应写入等待 GPU 资源的 statusText")
    }
}
