package com.github.project_fredica.worker

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.db.TaskService
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import java.util.PriorityQueue
import kotlin.coroutines.resume
import kotlin.time.TimeSource

/**
 * 全局 GPU 资源优先级锁。
 *
 * ## 设计目的
 * 家庭用户 GPU 显存有限，多个 GPU 密集型任务（faster-whisper 推理、ffmpeg 硬件加速转码）
 * 同时运行会导致 OOM 或严重降速。本锁保证同一时刻最多 1 个 GPU 任务在执行，
 * 并按优先级+时间序决定等待者的唤醒顺序。
 *
 * ## 优先级规则
 * - priority 值越大越优先（0-20）
 * - 1-10：重型 GPU 任务（ASR/转录）
 * - 11-20：轻型 GPU 任务（转码等）
 * - 0：最低优先级
 * - 同优先级按到达时间排序（先到先得，seq 单调递增）
 *
 * ## 实现
 * 基于 `Mutex`（保护内部状态）+ `PriorityQueue<WaitEntry>` + `CancellableContinuation`：
 * - `acquire()` 时若锁空闲则直接获取；否则入队挂起
 * - `release()` 时从队列中取出最高优先级的等待者，直接转移锁（occupied 保持 true）
 * - 取消时通过 `invokeOnCancellation` 从队列移除
 *
 * ## 与 WorkerEngine 并发控制的关系
 * WorkerEngine 的 Semaphore 限制同时运行的 Task 总数（含 CPU 任务），
 * GpuResourceLock 进一步将 GPU 任务的并发数限制为 1，两者互补。
 *
 * ## 使用方式
 * GPU 密集型 Executor 在 `executeWithSignals()` 中调用 [withGpuLock] 包裹业务逻辑：
 * - `TranscribeExecutor`：始终持锁（faster-whisper 推理始终占用 GPU）
 * - `TranscodeMp4Executor`：仅 `hw_accel != "cpu"` 时持锁
 */
object GpuResourceLock {
    private val mutex = Mutex()                    // 保护内部状态
    private val waitQueue = PriorityQueue<WaitEntry>(
        compareByDescending<WaitEntry> { it.priority }.thenBy { it.seq }
    )
    private var occupied = false                   // 锁是否被占用
    private var seqCounter = 0L                    // 单调递增序号

    private val logger = createLogger()
    private val clock = TimeSource.Monotonic

    private class WaitEntry(
        val priority: Int,
        val seq: Long,
        val continuation: CancellableContinuation<Unit>,
        val taskId: String,
    )

    /**
     * 获取 GPU 锁后执行 [block]，结束后自动释放。
     * 高优先级任务优先获取锁；同优先级按到达时间排序。
     *
     * @param taskId 当前任务 ID，用于等待时更新 statusText
     * @param priority 优先级（0-20，越大越优先）
     * @param block 持锁期间执行的挂起函数
     * @return [block] 的返回值
     */
    suspend fun <T> withGpuLock(
        taskId: String,
        priority: Int,
        block: suspend () -> T,
    ): T {
        val waitStart = clock.markNow()
        acquire(taskId, priority)
        val waitMs = waitStart.elapsedNow().inWholeMilliseconds
        if (waitMs > 50) {
            logger.debug("GpuResourceLock: 任务 $taskId (priority=$priority) 等待 ${waitMs}ms 后获取 GPU 锁")
        } else {
            logger.debug("GpuResourceLock: 任务 $taskId (priority=$priority) 直接获取 GPU 锁（无竞争）")
        }
        val holdStart = clock.markNow()
        try {
            return block()
        } finally {
            val holdMs = holdStart.elapsedNow().inWholeMilliseconds
            logger.debug("GpuResourceLock: 任务 $taskId 释放 GPU 锁（持锁 ${holdMs}ms）")
            release()
        }
    }

    private suspend fun acquire(taskId: String, priority: Int) {
        mutex.lock()
        if (!occupied) {
            occupied = true
            mutex.unlock()
            // 无竞争，清除可能残留的 statusText（no-op: null → null）
            TaskService.repo.updateStatusText(taskId, null)
            return
        }
        // 锁已被占用 → 写入等待提示，入队挂起
        logger.info("GpuResourceLock: 任务 $taskId (priority=$priority) 等待 GPU 资源…")
        TaskService.repo.updateStatusText(taskId, "等待 GPU 资源…")
        val seq = seqCounter++
        suspendCancellableCoroutine { cont ->
            waitQueue.add(WaitEntry(priority, seq, cont, taskId))
            cont.invokeOnCancellation {
                // 取消时从队列移除（在 mutex 外操作 PriorityQueue 是安全的，
                // 因为此时 release() 持有 mutex 才会 poll，而 cancel 发生在挂起点）
                waitQueue.removeAll { it.seq == seq }
            }
            mutex.unlock()
        }
        // 被唤醒后清除等待提示
        TaskService.repo.updateStatusText(taskId, null)
    }

    private suspend fun release() {
        mutex.lock()
        val next = waitQueue.poll()
        if (next != null) {
            // 唤醒最高优先级的等待者（锁直接转移，occupied 保持 true）
            mutex.unlock()
            next.continuation.resume(Unit)
        } else {
            occupied = false
            mutex.unlock()
        }
    }

    /**
     * 仅供测试使用：重置内部状态。
     * 生产代码不应调用此方法。
     */
    internal fun resetForTest() {
        occupied = false
        seqCounter = 0L
        waitQueue.clear()
    }
}
