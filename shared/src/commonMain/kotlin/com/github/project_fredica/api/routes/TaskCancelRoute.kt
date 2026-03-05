package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.TaskService
import com.github.project_fredica.worker.TaskCancelService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST /api/v1/TaskCancelRoute
 *
 * 取消一个正在执行或等待中的任务：
 *  - 若任务正在执行（WebSocket 已注册取消信号）：发送信号，Python 端收到 cancel 命令后停止下载
 *  - 若任务仍在排队（pending / claimed，尚未注册信号）：直接将 DB 状态置为 cancelled
 */
object TaskCancelRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "取消正在执行或等待中的任务"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<TaskCancelParam>().getOrThrow()

        // 优先通过信号取消正在运行的 WebSocket 任务
        val signalled = TaskCancelService.cancel(p.taskId)

        // 若信号未找到（任务还未进入 execute），直接更新 DB 中的 pending/claimed 任务
        if (!signalled) {
            val task = TaskService.repo.findById(p.taskId)
            if (task != null && (task.status == "pending" || task.status == "claimed")) {
                TaskService.repo.updateStatus(p.taskId, "cancelled", error = "用户已取消", errorType = "CANCELLED")
            }
        }

        return buildValidJson { kv("signalled", signalled) }
    }
}

@Serializable
data class TaskCancelParam(@SerialName("task_id") val taskId: String)
