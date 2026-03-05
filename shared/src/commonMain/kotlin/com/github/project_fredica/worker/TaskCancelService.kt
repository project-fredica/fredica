package com.github.project_fredica.worker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 运行中任务的取消信号注册表。
 *
 * Executor 在启动 WebSocket 长任务前调用 [register] 注册取消信号；
 * 前端触发取消时，[com.github.project_fredica.api.routes.TaskCancelRoute]
 * 调用 [cancel] 完成该信号，从而通知 WebSocket 协程向 Python 端发送 cancel 命令。
 *
 * 任务结束后须调用 [unregister] 清理，防止内存泄漏。
 */
object TaskCancelService {
    private val map = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val mutex = Mutex()

    /** 注册任务的取消信号，返回 Deferred，Executor await 它以感知取消。 */
    suspend fun register(taskId: String): CompletableDeferred<Unit> = mutex.withLock {
        CompletableDeferred<Unit>().also { map[taskId] = it }
    }

    /**
     * 触发取消信号。
     * @return true = 信号已发出；false = 任务未在运行（未注册）
     */
    suspend fun cancel(taskId: String): Boolean = mutex.withLock {
        map[taskId]?.complete(Unit) ?: false
    }

    /** 任务结束后注销。 */
    suspend fun unregister(taskId: String) = mutex.withLock {
        map.remove(taskId)
    }
}
