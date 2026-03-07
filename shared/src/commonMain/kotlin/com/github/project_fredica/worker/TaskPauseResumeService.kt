package com.github.project_fredica.worker

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TaskPauseResumeChannels(
    val pause: Channel<Unit>,
    val resume: Channel<Unit>,
)

/**
 * 运行中任务的暂停/恢复信号注册表（内存级，进程内有效）。
 *
 * ## 工作流程
 * 1. Executor 在调用 `PythonUtil.websocketTask()` 之前调用 [register]，
 *    拿到一对 Channel（pause / resume），传给 websocketTask。
 * 2. websocketTask 内部 `select` 同时监听 pauseChannel 和 resumeChannel：
 *    - 收到 pause 信号 → 向 Python 发送 `{"type":"pause"}` → Python 清除 resume_event
 *    - 收到 resume 信号 → 向 Python 发送 `{"type":"resume"}` → Python 设置 resume_event
 * 3. `TaskPauseRoute` / `TaskResumeRoute` 分别调用 [pause] / [resume] 投递信号。
 * 4. Executor 的 finally 块调用 [unregister] 关闭 Channel 并清理注册表。
 *
 * ## 与 TaskCancelService 的区别
 * - 取消：用 CompletableDeferred（一次性，完成后不可重置）
 * - 暂停/恢复：用 Channel（可多次投递，支持反复暂停/恢复）
 *
 * ## 线程安全
 * 所有操作通过 [Mutex] 串行化，可在任意协程上下文中安全调用。
 */
object TaskPauseResumeService {
    private val map = mutableMapOf<String, TaskPauseResumeChannels>()
    private val mutex = Mutex()

    /** 注册任务的暂停/恢复 Channel，返回 Channel 对，供 websocketTask 监听。 */
    suspend fun register(taskId: String): TaskPauseResumeChannels = mutex.withLock {
        TaskPauseResumeChannels(
            pause  = Channel(Channel.BUFFERED),
            resume = Channel(Channel.BUFFERED),
        ).also { map[taskId] = it }
    }

    /**
     * 向正在运行的任务发送暂停信号。
     * @return true = 信号已投递；false = 任务未在运行
     */
    suspend fun pause(taskId: String): Boolean = mutex.withLock {
        map[taskId]?.pause?.trySend(Unit)?.isSuccess ?: false
    }

    /**
     * 向暂停中的任务发送恢复信号。
     * @return true = 信号已投递；false = 任务未在运行
     */
    suspend fun resume(taskId: String): Boolean = mutex.withLock {
        map[taskId]?.resume?.trySend(Unit)?.isSuccess ?: false
    }

    /** 任务结束后注销，关闭 Channel 防止内存泄漏。 */
    suspend fun unregister(taskId: String) = mutex.withLock {
        map[taskId]?.pause?.close()
        map[taskId]?.resume?.close()
        map.remove(taskId)
    }
}
