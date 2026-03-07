package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.loadJsonModel
import kotlinx.serialization.Serializable

object BilibiliFavoriteGetVideoListRoute : FredicaApi.Route {
    override val mode: FredicaApi.Route.Mode
        get() = FredicaApi.Route.Mode.Post
    override val desc: String
        get() = "获取bilibili收藏夹中的视频列表"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<BilibiliFavoriteGetVideoListParam>().getOrThrow()
        val body = buildValidJson { kv("fid", p.fid) }
        return ValidJsonString(FredicaApi.PyUtil.post("/bilibili/favorite/get-video-list", body.str))
    }
}

@Serializable
data class BilibiliFavoriteGetVideoListParam(
    val fid: String,
)
