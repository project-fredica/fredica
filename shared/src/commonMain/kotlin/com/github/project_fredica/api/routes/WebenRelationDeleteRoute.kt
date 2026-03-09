package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenRelationService
import kotlinx.serialization.Serializable

/**
 * POST /api/v1/WebenRelationDeleteRoute
 *
 * 删除关系边（按 relation id）。
 */
object WebenRelationDeleteRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "删除概念关系边（按 id）"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<WebenRelationDeleteParam>().getOrThrow()
        WebenRelationService.repo.deleteById(p.id)
        return buildValidJson { kv("ok", true) }
    }
}

@Serializable
data class WebenRelationDeleteParam(val id: String)
