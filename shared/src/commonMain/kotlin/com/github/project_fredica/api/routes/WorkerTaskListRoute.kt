package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.TaskService
import kotlinx.serialization.encodeToString

/**
 * GET /api/v1/WorkerTaskListRoute[?pipeline_id=...&status=...]
 *
 * Lists all worker tasks with optional filters.
 * param is Map<String, List<String>> JSON (e.g. {"pipeline_id":["uuid"],"status":["pending"]})
 */
object WorkerTaskListRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "列出 Worker 任务（可按 pipeline_id / status 过滤）"

    override suspend fun handler(param: String): ValidJsonString {
        val query = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val pipelineId = query["pipeline_id"]?.firstOrNull()
        val status     = query["status"]?.firstOrNull()
        val tasks = TaskService.repo.listAll(pipelineId = pipelineId, status = status)
        return ValidJsonString(AppUtil.GlobalVars.json.encodeToString(tasks))
    }
}
