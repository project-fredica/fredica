package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenNoteService
import kotlinx.serialization.Serializable

/**
 * POST /api/v1/WebenNoteDeleteRoute
 */
object WebenNoteDeleteRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "删除概念笔记（按 id）"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<WebenNoteDeleteParam>().getOrThrow()
        WebenNoteService.repo.deleteById(p.id)
        return buildValidJson { kv("ok", true) }
    }
}

@Serializable
data class WebenNoteDeleteParam(val id: String)
