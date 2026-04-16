package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.auth.AuthRole
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.Serializable

object BilibiliFavoriteGetPageRoute : FredicaApi.Route {
    override val mode: FredicaApi.Route.Mode
        get() = FredicaApi.Route.Mode.Post
    override val desc: String
        get() = "获取bilibili收藏夹指定页的视频列表"
    override val minRole = AuthRole.GUEST

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val p = param.loadJsonModel<BilibiliFavoriteGetPageParam>().getOrThrow()
        val body = buildJsonObject { put("fid", p.fid); put("page", p.page) }.toValidJson()
        return ValidJsonString(FredicaApi.PyUtil.post("/bilibili/favorite/get-page", body.str))
    }
}

@Serializable
data class BilibiliFavoriteGetPageParam(
    val fid: String,
    val page: Int,
)
