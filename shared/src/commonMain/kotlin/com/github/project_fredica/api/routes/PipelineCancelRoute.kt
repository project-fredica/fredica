package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.PipelineService
import kotlinx.serialization.Serializable

/**
 * POST /api/v1/PipelineCancelRoute
 *
 * Cancels all pending tasks in the pipeline and marks it cancelled.
 * Running tasks are not interrupted (they finish normally).
 */
object PipelineCancelRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "取消流水线（将所有 pending 任务设为 cancelled）"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<PipelineCancelParam>().getOrThrow()
        val cancelled = PipelineService.repo.cancel(p.id)
        return buildValidJson { kv("cancelled_count", cancelled) }
    }
}

@Serializable
data class PipelineCancelParam(val id: String)
