package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.TaskService
import kotlinx.serialization.encodeToString

/**
 * GET /api/v1/WorkerTaskListRoute[?id=...&pipeline_id=...&status=...&material_id=...&category_id=...&page=1&page_size=20&sort=desc]
 *
 * Lists worker tasks with optional filters, pagination and sort.
 * Returns TaskListResult { items: List<Task>, total: Int }.
 *
 * Special status values: `pending`=等待中(pending+claimed), `running`=运行中(running且未暂停),
 * `paused`=已暂停(running+is_paused=1); other values match exactly.
 * sort: "desc" (default, newest first) or "asc".
 */
object WorkerTaskListRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "列出 Worker 任务（可按 id/pipeline_id/status/material_id/category_id 过滤，支持分页和排序）"

    override suspend fun handler(param: String): ValidJsonString {
        val query      = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val taskId     = query["id"]?.firstOrNull()
        val pipelineId = query["pipeline_id"]?.firstOrNull()
        val status     = query["status"]?.firstOrNull()
        val materialId = query["material_id"]?.firstOrNull()
        val categoryId = query["category_id"]?.firstOrNull()
        val page       = query["page"]?.firstOrNull()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val pageSize   = query["page_size"]?.firstOrNull()?.toIntOrNull()?.coerceIn(1, 200) ?: 20
        val sortDesc   = (query["sort"]?.firstOrNull() ?: "desc") != "asc"

        val result = TaskService.repo.listAll(
            taskId     = taskId,
            pipelineId = pipelineId,
            status     = status,
            materialId = materialId,
            categoryId = categoryId,
            page       = page,
            pageSize   = pageSize,
            sortDesc   = sortDesc,
        )
        return ValidJsonString(AppUtil.GlobalVars.json.encodeToString(result))
    }
}
