package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.github.project_fredica.db.TaskService
import kotlinx.serialization.encodeToString
import com.github.project_fredica.auth.AuthRole

/**
 * GET /api/v1/WorkerTaskWfIdListRoute?task_type=DOWNLOAD_TORCH[&page=1&page_size=20]
 *
 * 按任务类型查询去重的 workflow_run_id 列表，按该 workflow 下最新任务的创建时间降序排列，支持分页。
 * 返回 WorkflowRunIdListResult { ids: List<String>, total: Int }。
 *
 * 典型用途：torch 配置页展示历史下载任务列表，前端用每个 id 渲染 WorkflowInfoPanel。
 */
object WorkerTaskWfIdListRoute : FredicaApi.Route {
    private val logger = createLogger()

    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "按任务类型查询去重 workflow_run_id 列表（分页，按最新任务时间降序）"
    override val minRole = AuthRole.TENANT

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        return try {
            val query    = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
            val taskType = query["task_type"]?.firstOrNull() ?: ""
            val page     = query["page"]?.firstOrNull()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val pageSize = query["page_size"]?.firstOrNull()?.toIntOrNull()?.coerceIn(1, 100) ?: 20

            logger.info("[WorkerTaskWfIdListRoute] task_type=$taskType page=$page page_size=$pageSize")

            if (taskType.isBlank()) {
                return buildJsonObject { put("error", "task_type is required") }.toValidJson()
            }

            val result = TaskService.repo.listWorkflowRunIdsByType(
                type     = taskType,
                page     = page,
                pageSize = pageSize,
            )
            logger.info("[WorkerTaskWfIdListRoute] 返回 ${result.ids.size}/${result.total} 条 workflow_run_id")
            ValidJsonString(AppUtil.GlobalVars.json.encodeToString(result))
        } catch (e: Throwable) {
            logger.warn("[WorkerTaskWfIdListRoute] 查询失败: ${e.message}")
            buildJsonObject { put("error", e.message ?: "unknown") }.toValidJson()
        }
    }
}
