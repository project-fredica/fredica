package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.db.BilibiliSubtitleMetaCache
import com.github.project_fredica.db.BilibiliSubtitleMetaCacheService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object BilibiliVideoSubtitleRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "获取 B 站视频字幕元信息"

    private val logger = createLogger { "BilibiliVideoSubtitleRoute" }

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<BilibiliVideoSubtitleParam>().getOrThrow()
        logger.debug("请求 bvid=${p.bvid} page=${p.pageIndex} is_update=${p.isUpdate}")
        val cache = BilibiliSubtitleMetaCacheService.repo

        if (!p.isUpdate) {
            val cached = cache.queryBest(p.bvid, p.pageIndex)
            if (cached != null) {
                logger.debug("命中缓存 bvid=${p.bvid} page=${p.pageIndex} queried_at=${cached.queriedAt}")
                return ValidJsonString(cached.rawResult)
            }
            logger.debug("无缓存，发起 Python 请求 bvid=${p.bvid} page=${p.pageIndex}")
        } else {
            logger.debug("强制刷新，跳过缓存 bvid=${p.bvid} page=${p.pageIndex}")
        }

        val cfg = AppConfigService.repo.getConfig()
        val pyBody = buildValidJson {
            kv("sessdata", cfg.bilibiliSessdata)
            kv("bili_jct", cfg.bilibiliBiliJct)
            kv("buvid3", cfg.bilibiliBuvid3)
            kv("buvid4", cfg.bilibiliBuvid4)
            kv("dedeuserid", cfg.bilibiliDedeuserid)
            kv("ac_time_value", cfg.bilibiliAcTimeValue)
            kv("proxy", cfg.bilibiliProxy)
        }
        val raw = FredicaApi.PyUtil.post("/bilibili/video/subtitle-meta/${p.bvid}/${p.pageIndex}", pyBody.str)
        val isSuccess = runCatching {
            val obj = raw.loadJson().getOrThrow() as? JsonObject
            val code = (obj?.get("code") as? JsonPrimitive)?.content?.toIntOrNull()
            code == 0
        }.getOrDefault(false)

        cache.insert(
            BilibiliSubtitleMetaCache(
                bvid = p.bvid,
                pageIndex = p.pageIndex,
                queriedAt = System.currentTimeMillis() / 1000L,
                rawResult = raw,
                isSuccess = isSuccess,
            )
        )
        logger.info("已写入缓存 bvid=${p.bvid} page=${p.pageIndex} is_success=$isSuccess")
        return ValidJsonString(raw)
    }
}

@Serializable
data class BilibiliVideoSubtitleParam(
    val bvid: String,
    @SerialName("page_index") val pageIndex: Int = 0,
    @SerialName("is_update") val isUpdate: Boolean = false,
)
