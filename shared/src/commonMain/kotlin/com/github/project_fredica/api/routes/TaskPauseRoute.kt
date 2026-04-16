package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.auth.AuthRole
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.github.project_fredica.db.TaskStatusService
import com.github.project_fredica.worker.TaskPauseResumeService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST /api/v1/TaskPauseRoute
 *
 * 暂停正在执行的下载任务：向 WebSocket Channel 投递 pause 信号，
 * websocketTask 协程将向 Python 端发送 {"command":"pause"}，
 * Python 端在下一个 chunk 循环检查 wait_if_paused() 时挂起。
 */
object TaskPauseRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.TENANT
    override val desc = "暂停正在执行的下载任务"

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val p = param.loadJsonModel<TaskPauseParam>().getOrThrow()
        val signalled = TaskPauseResumeService.pause(p.taskId)
        if (signalled) {
            TaskStatusService.updatePaused(p.taskId, true)
        }
        return buildJsonObject { put("signalled", signalled) }.toValidJson()
    }
}

@Serializable
data class TaskPauseParam(@SerialName("task_id") val taskId: String)
