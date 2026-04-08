package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenNoteService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * POST /api/v1/WebenNoteDeleteRoute
 *
 * 删除概念笔记（按 note.id）。
 */
object WebenNoteDeleteRoute : FredicaApi.Route {
    private val logger = createLogger { "WebenNoteDeleteRoute" }

    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "删除概念笔记（按 id）"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<WebenNoteDeleteParam>().getOrThrow()
        logger.debug("WebenNoteDeleteRoute: 删除笔记 id=${p.id}")
        WebenNoteService.repo.deleteById(p.id)
        logger.info("WebenNoteDeleteRoute: 笔记已删除 id=${p.id}")
        return buildJsonObject { put("ok", true) }.toValidJson()
    }
}

@Serializable
data class WebenNoteDeleteParam(val id: String)
