package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.TaskService
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
    override val desc = "暂停正在执行的下载任务"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<TaskPauseParam>().getOrThrow()
        val signalled = TaskPauseResumeService.pause(p.taskId)
        if (signalled) {
            TaskService.repo.updatePaused(p.taskId, true)
        }
        return buildValidJson { kv("signalled", signalled) }
    }
}

@Serializable
data class TaskPauseParam(@SerialName("task_id") val taskId: String)
