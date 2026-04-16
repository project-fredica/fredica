package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.github.project_fredica.db.MaterialWorkflowService
import kotlinx.serialization.encodeToString
import com.github.project_fredica.auth.AuthRole

/**
 * GET /api/v1/MaterialWorkflowStatusRoute?material_id=<id>[&mode=history&page=1&page_size=10]
 *
 * 查询与某个素材关联的工作流及其子任务列表，支持两种模式：
 *
 * **mode=active（默认）**
 *   返回该素材所有活跃（非终态）WorkflowRun 及其子任务，不分页。
 *   适用场景：页面刷新时恢复下载/转码任务的进度显示。
 *   响应：`{ "workflow_runs": [ { "workflow_run": {...}, "tasks": [...] } ] }`
 *
 * **mode=history**
 *   返回该素材所有终态（completed/failed/cancelled）WorkflowRun 及其子任务，分页。
 *   响应：`{ "items": [...], "total": N }`
 *
 * 对账：active 模式首次请求时自动触发 material 维度的启动级对账，
 * 确保 WorkflowRun 状态与 Task 实际状态一致。
 */
object MaterialWorkflowStatusRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "查询素材的活跃/历史工作流（含任务列表）"
    override val minRole = AuthRole.TENANT

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val query      = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val materialId = query["material_id"]?.firstOrNull()
            ?: return buildJsonObject { put("error", "MISSING_MATERIAL_ID") }.toValidJson()
        val queryMode  = query["mode"]?.firstOrNull() ?: "active"

        return when (queryMode) {
            "active" -> {
                val result = MaterialWorkflowService.queryActive(materialId)
                ValidJsonString(AppUtil.GlobalVars.json.encodeToString(result))
            }
            "history" -> {
                val page     = query["page"]?.firstOrNull()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val pageSize = query["page_size"]?.firstOrNull()?.toIntOrNull()?.coerceIn(1, 100) ?: 10
                val result   = MaterialWorkflowService.queryHistory(materialId, page, pageSize)
                ValidJsonString(AppUtil.GlobalVars.json.encodeToString(result))
            }
            else -> buildJsonObject { put("error", "UNKNOWN_MODE") }.toValidJson()
        }
    }
}
