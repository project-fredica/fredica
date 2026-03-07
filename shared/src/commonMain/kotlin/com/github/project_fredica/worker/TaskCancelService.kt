package com.github.project_fredica.worker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 运行中任务的取消信号注册表（内存级，进程内有效）。
 *
 * ## 工作流程
 * 1. Executor 在调用 `PythonUtil.websocketTask()` 之前调用 [register]，
 *    拿到一个 [CompletableDeferred]，传给 websocketTask 作为 cancelSignal。
 * 2. websocketTask 内部 `select { cancelSignal.onAwait { ... } }` 监听该信号。
 * 3. 前端点击取消 → `TaskCancelRoute` 调用 [cancel] → Deferred 完成 →
 *    websocketTask 向 Python 端发送 `{"type":"cancel"}` → Python 停止子进程。
 * 4. Executor 的 finally 块调用 [unregister] 清理注册表。
 *
 * ## 级联取消
 * `TaskCancelRoute` 会取消同一 WorkflowRun 内所有活跃任务，因此可能对多个
 * taskId 依次调用 [cancel]。每个 Executor 独立持有自己的 Deferred，互不干扰。
 *
 * ## 线程安全
 * 所有操作通过 [Mutex] 串行化，可在任意协程上下文中安全调用。
 */
object TaskCancelService {
    private val map = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val mutex = Mutex()

    /**
     * 注册任务的取消信号。
     *
     * 若同一 taskId 重复注册（理论上不应发生），旧 Deferred 会被覆盖。
     * @return 新建的 [CompletableDeferred]，传给 websocketTask 的 cancelSignal 参数
     */
    suspend fun register(taskId: String): CompletableDeferred<Unit> = mutex.withLock {
        CompletableDeferred<Unit>().also { map[taskId] = it }
    }

    /**
     * 触发指定任务的取消信号。
     *
     * 调用后 websocketTask 会在下一个 select 循环感知到信号并向 Python 发送 cancel。
     * @return true = 信号已发出（任务正在运行）；false = 任务未注册（已结束或尚未启动）
     */
    suspend fun cancel(taskId: String): Boolean = mutex.withLock {
        map[taskId]?.complete(Unit) ?: false
    }

    /**
     * 任务结束后注销，释放 Deferred 引用防止内存泄漏。
     * 应在 Executor 的 finally 块中调用，无论任务成功、失败还是取消。
     */
    suspend fun unregister(taskId: String) = mutex.withLock {
        map.remove(taskId)
    }
}
