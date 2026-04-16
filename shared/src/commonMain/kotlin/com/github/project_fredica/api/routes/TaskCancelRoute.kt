package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.auth.AuthRole
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.github.project_fredica.db.TaskStatusService
import com.github.project_fredica.worker.TaskCancelService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST /api/v1/TaskCancelRoute
 *
 * 取消一个任务，并级联取消同一 WorkflowRun 内所有其他活跃任务。
 *
 * 级联语义：WorkflowRun 是一个整体（DAG），取消其中任意一个任务意味着
 * 整个运行实例应当停止，因此同一 workflow_run_id 下所有 pending/claimed/running
 * 的任务都会被一并取消。
 *
 * 取消逻辑（对每个活跃任务）：
 *  - 若任务正在执行（WebSocket 已注册取消信号）：发送信号，Python 端收到 cancel 命令后停止
 *  - 若任务仍在排队（pending / claimed，尚未注册信号）：直接将 DB 状态置为 cancelled
 */
object TaskCancelRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.TENANT
    override val desc = "取消任务并级联取消同一 WorkflowRun 内所有活跃任务"

    private val activeStatuses = setOf("pending", "claimed", "running")

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val p = param.loadJsonModel<TaskCancelParam>().getOrThrow()

        // 查找目标任务，获取 workflowRunId 以便级联取消；首次访问时对父 WorkflowRun 进行对账
        val targetTask = TaskStatusService.findById(p.taskId)
            ?: return buildJsonObject { put("signalled", false); put("cancelled_count", 0) }.toValidJson()

        // 收集同一 WorkflowRun 内所有活跃任务（含目标任务本身）；首次访问时对该 WorkflowRun 进行对账
        val siblings = TaskStatusService.listByWorkflowRun(targetTask.workflowRunId)
            .filter { it.status in activeStatuses }

        var signalled = false
        var cancelledCount = 0

        for (task in siblings) {
            val sig = TaskCancelService.cancel(task.id)
            if (task.id == p.taskId) signalled = sig
            if (!sig && (task.status == "pending" || task.status == "claimed")) {
                TaskStatusService.updateStatus(task.id, "cancelled", error = "用户已取消", errorType = "CANCELLED")
                cancelledCount++
            } else if (sig) {
                cancelledCount++
            }
        }

        return buildJsonObject {
            put("signalled", signalled)
            put("cancelled_count", cancelledCount)
        }.toValidJson()
    }
}

@Serializable
data class TaskCancelParam(@SerialName("task_id") val taskId: String)
