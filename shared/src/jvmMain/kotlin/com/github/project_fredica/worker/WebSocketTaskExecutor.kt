package com.github.project_fredica.worker

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskService
import kotlinx.coroutines.CompletableDeferred

/**
 * 基于 Python WebSocket 长任务协议的 Executor 抽象基类。
 *
 * 封装了所有子类共用的生命周期样板：
 * - 在 [execute] 开始时向 [TaskCancelService] / [TaskPauseResumeService] 注册信号
 * - 在 [execute] 结束时（无论成功/失败/取消）统一注销信号并重置 DB 暂停状态
 *
 * 子类只需实现 [executeWithSignals]，专注于业务逻辑，无需关心信号的注册与清理。
 *
 * ## 取消判断
 * [executeWithSignals] 的 catch 块可通过 `cancelSignal.isCompleted` 判断是否为用户主动取消。
 */
abstract class WebSocketTaskExecutor : TaskExecutor {

    final override suspend fun execute(task: Task): ExecuteResult {
        val logger = createLogger()
        val cancelSignal = TaskCancelService.register(task.id)
        val pauseResumeChannels = TaskPauseResumeService.register(task.id)
        return try {
            logger.debug("websocketTask start execute , task.id is ${task.id}")
            executeWithSignals(task, cancelSignal, pauseResumeChannels)
        } finally {
            logger.debug("websocketTask stop , task.id is ${task.id}")
            TaskCancelService.unregister(task.id)
            TaskPauseResumeService.unregister(task.id)
            try {
                TaskService.repo.updatePaused(task.id, false)
            } catch (err: Exception) {
                logger.warn("websocketTask updatePaused failed , task.id is ${task.id} , error is $err")
            }
        }
    }

    /**
     * 子类实现的业务逻辑入口。信号已由基类注册，结束后由基类清理。
     *
     * @param cancelSignal       取消信号，传给 [com.github.project_fredica.python.PythonUtil.Py314Embed.PyUtilServer.websocketTask]
     * @param pauseResumeChannels 暂停/恢复 Channel 对，同上
     */
    protected abstract suspend fun executeWithSignals(
        task: Task,
        cancelSignal: CompletableDeferred<Unit>,
        pauseResumeChannels: TaskPauseResumeChannels,
    ): ExecuteResult
}
