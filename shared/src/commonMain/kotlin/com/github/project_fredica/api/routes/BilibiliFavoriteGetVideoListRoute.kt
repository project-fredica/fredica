package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.get
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

object BilibiliFavoriteGetVideoListRoute : FredicaApi.Route {
    override val mode: FredicaApi.Route.Mode
        get() = FredicaApi.Route.Mode.Post
    override val desc: String
        get() = "获取bilibili收藏夹中的视频列表"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<BilibiliFavoriteGetVideoListParam>().getOrThrow()
        return ValidJsonString(FredicaApi.PyUtil.get("/bilibili/favorite/get-video-list/${p.fid}"))
    }
}

@Serializable
data class BilibiliFavoriteGetVideoListParam(
    val fid: String,
)
