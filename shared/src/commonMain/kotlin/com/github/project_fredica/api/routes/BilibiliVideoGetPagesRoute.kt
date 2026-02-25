package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.get
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import kotlinx.serialization.Serializable

object BilibiliVideoGetPagesRoute : FredicaApi.Route {
    override val mode: FredicaApi.Route.Mode
        get() = FredicaApi.Route.Mode.Post
    override val desc: String
        get() = "获取bilibili视频的全部分P列表"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<BilibiliVideoGetPagesParam>().getOrThrow()
        val res = FredicaApi.PyUtil.get("/bilibili/video/get-pages/${p.bvid}")
        return ValidJsonString(res)
    }
}

@Serializable
data class BilibiliVideoGetPagesParam(val bvid: String)

@Serializable
data class BilibiliPageInfo(
    val page: Int,
    val title: String,
    val duration: Int,
    val cover: String = "",
)
