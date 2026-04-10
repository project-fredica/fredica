package com.github.project_fredica.worker

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.db.TaskService
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.TimeSource

/**
 * 全局 GPU 资源互斥锁。
 *
 * ## 设计目的
 * 家庭用户 GPU 显存有限，多个 GPU 密集型任务（faster-whisper 推理、ffmpeg 硬件加速转码）
 * 同时运行会导致 OOM 或严重降速。本锁保证同一时刻最多 1 个 GPU 任务在执行。
 *
 * ## 实现
 * 基于 `Semaphore(1)` 实现互斥语义：
 * - 同一时刻最多 1 个协程持有 permit
 * - `withPermit` 基于 `try/finally`，即使 `block()` 抛异常也能正确释放
 * - 无嵌套持锁场景，不存在死锁风险
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
    private val semaphore = Semaphore(1)
    private val logger = createLogger()
    private val clock = TimeSource.Monotonic

    /**
     * 获取 GPU 锁后执行 [block]，结束后自动释放。
     *
     * 若锁已被占用，会先更新任务的 statusText 为"等待 GPU 资源…"提示用户，
     * 获取到锁后清除该 statusText。
     *
     * @param taskId 当前任务 ID，用于等待时更新 statusText
     * @param block  持锁期间执行的挂起函数
     * @return [block] 的返回值
     */
    suspend fun <T> withGpuLock(taskId: String, block: suspend () -> T): T {
        val needsWait = semaphore.availablePermits == 0
        if (needsWait) {
            logger.info("GpuResourceLock: 任务 $taskId 等待 GPU 资源…")
            TaskService.repo.updateStatusText(taskId, "等待 GPU 资源…")
        }
        val waitStart = clock.markNow()
        return semaphore.withPermit {
            if (needsWait) {
                val waitMs = waitStart.elapsedNow().inWholeMilliseconds
                logger.debug("GpuResourceLock: 任务 $taskId 等待 ${waitMs}ms 后获取 GPU 锁")
            } else {
                logger.debug("GpuResourceLock: 任务 $taskId 直接获取 GPU 锁（无竞争）")
            }
            // 清除等待提示（若之前未等待则为 no-op：null → null）
            TaskService.repo.updateStatusText(taskId, null)
            val holdStart = clock.markNow()
            try {
                block()
            } finally {
                val holdMs = holdStart.elapsedNow().inWholeMilliseconds
                logger.debug("GpuResourceLock: 任务 $taskId 释放 GPU 锁（持锁 ${holdMs}ms）")
            }
        }
    }
}
