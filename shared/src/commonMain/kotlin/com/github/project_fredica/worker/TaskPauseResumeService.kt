package com.github.project_fredica.worker

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TaskPauseResumeChannels(
    val pause: Channel<Unit>,
    val resume: Channel<Unit>,
)

/**
 * 运行中任务的暂停/恢复信号注册表。
 *
 * Executor 在启动 WebSocket 任务前调用 [register] 注册一对 Channel；
 * [TaskPauseRoute] / [TaskResumeRoute] 通过 [pause] / [resume] 向 Channel 投递信号，
 * websocketTask 内的协程监听后向 Python 端发送相应命令。
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
