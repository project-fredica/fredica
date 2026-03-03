package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.PipelineService
import com.github.project_fredica.db.TaskService
import kotlinx.serialization.encodeToString

/**
 * GET /api/v1/PipelineGetRoute?id=...
 *
 * Returns a single pipeline with all its tasks.
 * param is Map<String, List<String>> JSON (e.g. {"id":["uuid"]})
 */
object PipelineGetRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "获取单个流水线详情（含任务列表）"

    override suspend fun handler(param: String): ValidJsonString {
        val query = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val id = query["id"]?.firstOrNull()
            ?: return buildValidJson { kv("error", "missing_id") }

        val pipeline = PipelineService.repo.getById(id)
            ?: return buildValidJson { kv("error", "not_found"); kv("id", id) }

        val tasks = TaskService.repo.listByPipeline(id)

        return buildValidJson {
            kv("pipeline", ValidJsonString(AppUtil.GlobalVars.json.encodeToString(pipeline)))
            kv("tasks",    ValidJsonString(AppUtil.GlobalVars.json.encodeToString(tasks)))
        }
    }
}
