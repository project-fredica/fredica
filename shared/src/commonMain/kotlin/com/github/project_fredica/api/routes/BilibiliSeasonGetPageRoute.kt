package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.auth.AuthRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.Serializable

object BilibiliSeasonGetPageRoute : FredicaApi.Route {
    private val logger = createLogger()
    override val mode: FredicaApi.Route.Mode
        get() = FredicaApi.Route.Mode.Post
    override val desc: String
        get() = "获取bilibili合集(season)指定页的视频列表"
    override val minRole = AuthRole.GUEST

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val p = param.loadJsonModel<BilibiliSeasonGetPageParam>().getOrThrow()
        logger.debug("handler: seasonId=${p.seasonId} mid=${p.mid} page=${p.page}")
        val body = buildJsonObject {
            put("season_id", p.seasonId)
            put("mid", p.mid)
            put("page", p.page)
        }.toValidJson()
        return ValidJsonString(FredicaApi.PyUtil.post("/bilibili/season/get-page", body.str))
    }
}

@Serializable
data class BilibiliSeasonGetPageParam(
    @SerialName("season_id") val seasonId: String,
    val mid: String,
    val page: Int = 1,
)
