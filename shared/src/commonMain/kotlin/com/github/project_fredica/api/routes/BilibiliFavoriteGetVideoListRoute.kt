package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.loadJsonModel

object BilibiliFavoriteGetVideoListRoute : FredicaApi.Route {
    override val mode: FredicaApi.Route.Mode
        get() = FredicaApi.Route.Mode.Post
    override val desc: String
        get() = "获取bilibili收藏夹视频列表"

    override suspend fun handler(param: String): Unit {
        val p = param.loadJsonModel<BilibiliFavoriteGetVideoListParam>().getOrThrow()

    }
}

data class BilibiliFavoriteGetVideoListParam(
    val fid: String,
)