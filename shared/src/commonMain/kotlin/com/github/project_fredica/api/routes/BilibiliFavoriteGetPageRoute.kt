package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.loadJsonModel
import kotlinx.serialization.Serializable

object BilibiliFavoriteGetPageRoute : FredicaApi.Route {
    override val mode: FredicaApi.Route.Mode
        get() = FredicaApi.Route.Mode.Post
    override val desc: String
        get() = "获取bilibili收藏夹指定页的视频列表"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<BilibiliFavoriteGetPageParam>().getOrThrow()
        val body = buildValidJson { kv("fid", p.fid); kv("page", p.page) }
        return ValidJsonString(FredicaApi.PyUtil.post("/bilibili/favorite/get-page", body.str))
    }
}

@Serializable
data class BilibiliFavoriteGetPageParam(
    val fid: String,
    val page: Int,
)
