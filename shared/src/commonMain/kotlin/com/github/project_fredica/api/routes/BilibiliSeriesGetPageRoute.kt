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

object BilibiliSeriesGetPageRoute : FredicaApi.Route {
    private val logger = createLogger()
    override val mode: FredicaApi.Route.Mode
        get() = FredicaApi.Route.Mode.Post
    override val desc: String
        get() = "获取bilibili系列(series)指定页的视频列表"
    override val minRole = AuthRole.GUEST

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val p = param.loadJsonModel<BilibiliSeriesGetPageParam>().getOrThrow()
        logger.debug("handler: seriesId=${p.seriesId} mid=${p.mid} page=${p.page}")
        val body = buildJsonObject {
            put("series_id", p.seriesId)
            put("mid", p.mid)
            put("page", p.page)
        }.toValidJson()
        return ValidJsonString(FredicaApi.PyUtil.post("/bilibili/series/get-page", body.str))
    }
}

@Serializable
data class BilibiliSeriesGetPageParam(
    @SerialName("series_id") val seriesId: String,
    val mid: String,
    val page: Int = 1,
)
