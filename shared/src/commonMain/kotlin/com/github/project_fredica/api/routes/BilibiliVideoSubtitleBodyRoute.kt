package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.BilibiliSubtitleBodyCacheService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object BilibiliVideoSubtitleBodyRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "获取 B 站视频字幕内容"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<BilibiliVideoSubtitleBodyParam>().getOrThrow()
        val raw = BilibiliSubtitleBodyCacheService.fetchBilibiliSubtitleBody(p.subtitleUrl, isUpdate = p.isUpdate)
        return ValidJsonString(raw)
    }
}

@Serializable
data class BilibiliVideoSubtitleBodyParam(
    @SerialName("subtitle_url") val subtitleUrl: String,
    @SerialName("is_update") val isUpdate: Boolean = false,
)
