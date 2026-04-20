package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.auth.AuthRole
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.Serializable

object BilibiliFavoriteGetVideoListRoute : FredicaApi.Route {
    private val logger = createLogger()
    override val mode: FredicaApi.Route.Mode
        get() = FredicaApi.Route.Mode.Post
    override val desc: String
        get() = "获取bilibili收藏夹中的视频列表"
    override val minRole = AuthRole.GUEST

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val p = param.loadJsonModel<BilibiliFavoriteGetVideoListParam>().getOrThrow()
        logger.debug("handler: fid=${p.fid}")
        val body = buildJsonObject { put("fid", p.fid) }.toValidJson()
        return ValidJsonString(FredicaApi.PyUtil.post("/bilibili/favorite/get-video-list", body.str))
    }
}

@Serializable
data class BilibiliFavoriteGetVideoListParam(
    val fid: String,
)
