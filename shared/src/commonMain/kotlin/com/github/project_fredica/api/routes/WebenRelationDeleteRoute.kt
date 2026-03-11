package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenRelationService
import kotlinx.serialization.Serializable

/**
 * POST /api/v1/WebenRelationDeleteRoute
 *
 * 删除关系边（按 relation.id）。
 * 手动关系（is_manual=true）和 LLM 自动关系均可删除。
 */
object WebenRelationDeleteRoute : FredicaApi.Route {
    private val logger = createLogger { "WebenRelationDeleteRoute" }

    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "删除概念关系边（按 id）"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<WebenRelationDeleteParam>().getOrThrow()
        logger.debug("WebenRelationDeleteRoute: 删除关系 id=${p.id}")
        WebenRelationService.repo.deleteById(p.id)
        logger.info("WebenRelationDeleteRoute: 关系已删除 id=${p.id}")
        return buildValidJson { kv("ok", true) }
    }
}

@Serializable
data class WebenRelationDeleteParam(val id: String)
