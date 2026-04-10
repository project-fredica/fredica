package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.asr.route_ext.handler2
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object BilibiliVideoSubtitleBodyRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "获取 B 站视频字幕内容"

    override suspend fun handler(param: String): ValidJsonString = handler2(param)
}

@Serializable
data class BilibiliVideoSubtitleBodyParam(
    @SerialName("subtitle_url") val subtitleUrl: String,
    @SerialName("is_update") val isUpdate: Boolean = false,
)
