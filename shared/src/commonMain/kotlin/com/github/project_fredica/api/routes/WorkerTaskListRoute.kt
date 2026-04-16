package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.TaskStatusService
import kotlinx.serialization.encodeToString
import com.github.project_fredica.auth.AuthRole

/**
 * GET /api/v1/WorkerTaskListRoute[?id=...&workflow_run_id=...&status=...&material_id=...&category_id=...&page=1&page_size=20&sort=desc]
 *
 * 列出 Worker 任务，支持可选过滤、分页和排序。
 * 返回 TaskListResult { items: List<Task>, total: Int }。
 *
 * 特殊 status 值：`pending`=等待中(pending+claimed)，`running`=运行中(running且未暂停)，
 * `paused`=已暂停(running+is_paused=1)；其他值精确匹配。
 * sort: "desc"（默认，最新优先）或 "asc"。
 *
 * 启动对账：
 * - 按 material_id 过滤时，首次请求对该素材下所有非终态 WorkflowRun 进行对账
 * - 按 workflow_run_id 过滤时，首次请求对该 WorkflowRun 进行对账
 * - 不过滤时跳过对账（WorkerEngine 启动时已执行全局对账）
 */
object WorkerTaskListRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "列出 Worker 任务（可按 id/workflow_run_id/status/material_id/category_id 过滤，支持分页和排序，含首次启动对账）"
    override val minRole = AuthRole.TENANT

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val query         = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val taskId        = query["id"]?.firstOrNull()
        val workflowRunId = query["workflow_run_id"]?.firstOrNull()
        val status        = query["status"]?.firstOrNull()
        val materialId    = query["material_id"]?.firstOrNull()
        val categoryId    = query["category_id"]?.firstOrNull()
        val page          = query["page"]?.firstOrNull()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val pageSize      = query["page_size"]?.firstOrNull()?.toIntOrNull()?.coerceIn(1, 200) ?: 20
        val sortDesc      = (query["sort"]?.firstOrNull() ?: "desc") != "asc"

        // listAll 在首次调用时触发对账（StartupReconcileGuard 保护），后续跳过
        val result = TaskStatusService.listAll(
            taskId        = taskId,
            workflowRunId = workflowRunId,
            status        = status,
            materialId    = materialId,
            categoryId    = categoryId,
            page          = page,
            pageSize      = pageSize,
            sortDesc      = sortDesc,
        )
        return ValidJsonString(AppUtil.GlobalVars.json.encodeToString(result))
    }
}
