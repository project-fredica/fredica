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
 * POST /api/v1/TaskResumeRoute
 *
 * 恢复已暂停的下载任务：向 WebSocket Channel 投递 resume 信号，
 * websocketTask 协程将向 Python 端发送 {"command":"resume"}，
 * Python 端的 not_paused_event 被置位，wait_if_paused() 返回，下载继续。
 */
object TaskResumeRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.TENANT
    override val desc = "恢复已暂停的下载任务"

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val p = param.loadJsonModel<TaskResumeParam>().getOrThrow()
        val signalled = TaskPauseResumeService.resume(p.taskId)
        if (signalled) {
            TaskStatusService.updatePaused(p.taskId, false)
        }
        return buildJsonObject { put("signalled", signalled) }.toValidJson()
    }
}

@Serializable
data class TaskResumeParam(@SerialName("task_id") val taskId: String)
