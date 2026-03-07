package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.loadJsonModel
import kotlinx.serialization.Serializable

object BilibiliVideoGetPagesRoute : FredicaApi.Route {
    override val mode: FredicaApi.Route.Mode
        get() = FredicaApi.Route.Mode.Post
    override val desc: String
        get() = "获取bilibili视频的全部分P列表"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<BilibiliVideoGetPagesParam>().getOrThrow()
        val body = buildValidJson { kv("bvid", p.bvid) }
        val res = FredicaApi.PyUtil.post("/bilibili/video/get-pages/${p.bvid}", body.str)
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
