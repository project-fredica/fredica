package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.json
import com.github.project_fredica.db.PipelineService
import kotlinx.serialization.encodeToString

/**
 * GET /api/v1/PipelineListRoute
 *
 * Returns all pipeline instances ordered by created_at descending.
 * Each entry includes done_tasks / total_tasks for progress display.
 */
object PipelineListRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "列出所有流水线实例（含进度）"

    override suspend fun handler(param: String): ValidJsonString {
        val pipelines = PipelineService.repo.listAll()
        return ValidJsonString(AppUtil.GlobalVars.json.encodeToString(pipelines))
    }
}
