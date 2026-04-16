package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.loadJsonModel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.github.project_fredica.db.RestartTaskLogService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.github.project_fredica.auth.AuthRole

/**
 * POST /api/v1/RestartTaskLogUpdateDispositionRoute
 *
 * 更新重启中断任务日志的处置方式。
 *
 * 请求体：
 *   { "ids": ["..."] }                          — 按 IDs 批量更新（ids 优先）
 *   { "session_id": "..." }                     — 按 session_id 批量更新
 *   可选字段：
 *   { "new_workflow_run_id": "..." }             — 若提供则 disposition 为 recreated
 *
 * disposition 策略：
 *   - 提供 new_workflow_run_id → 强制 recreated
 *   - 否则 → dismissed
 *
 * 响应：{ "ok": true }
 */
object RestartTaskLogUpdateDispositionRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "更新重启中断任务日志的处置方式（dismissed / recreated）"
    override val minRole = AuthRole.ROOT

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val p = param.loadJsonModel<RestartTaskLogUpdateDispositionParam>().getOrThrow()

        val disposition = if (p.newWorkflowRunId != null) "recreated" else "dismissed"

        RestartTaskLogService.repo.updateDisposition(
            ids              = p.ids,
            sessionId        = p.sessionId,
            disposition      = disposition,
            newWorkflowRunId = p.newWorkflowRunId,
        )

        return buildJsonObject { put("ok", true) }.toValidJson()
    }
}

@Serializable
data class RestartTaskLogUpdateDispositionParam(
    val ids: List<String>? = null,
    @SerialName("session_id")        val sessionId: String? = null,
    @SerialName("new_workflow_run_id") val newWorkflowRunId: String? = null,
)
