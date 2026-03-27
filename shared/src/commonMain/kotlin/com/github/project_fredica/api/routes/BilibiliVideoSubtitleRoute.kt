package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.db.BilibiliSubtitleMetaCacheService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object BilibiliVideoSubtitleRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "获取 B 站视频字幕元信息"

    private val logger = createLogger { "BilibiliVideoSubtitleRoute" }

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<BilibiliVideoSubtitleParam>().getOrThrow()
        logger.debug("请求 bvid=${p.bvid} page=${p.pageIndex} is_update=${p.isUpdate}")

        val cfg = AppConfigService.repo.getConfig()

        val raw = BilibiliSubtitleMetaCacheService.fetchBilibiliSubtitleMeta(p.bvid, p.pageIndex, isUpdate = p.isUpdate, cfg)
        logger.debug("返回结果 bvid=${p.bvid} page=${p.pageIndex}")
        return ValidJsonString(raw)
    }
}

@Serializable
data class BilibiliVideoSubtitleParam(
    val bvid: String,
    @SerialName("page_index") val pageIndex: Int = 0,
    @SerialName("is_update") val isUpdate: Boolean = false,
)
