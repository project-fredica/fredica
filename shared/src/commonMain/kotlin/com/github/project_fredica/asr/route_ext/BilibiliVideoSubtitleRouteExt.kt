package com.github.project_fredica.asr.route_ext

import com.github.project_fredica.api.routes.BilibiliVideoSubtitleRoute
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.api.routes.BilibiliVideoSubtitleParam
import com.github.project_fredica.asr.service.BilibiliSubtitleMetaCacheService
import com.github.project_fredica.db.AppConfigService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val logger = createLogger { "BilibiliVideoSubtitleRoute" }

/** 获取 Bilibili 字幕元信息（字幕轨列表），通过 [BilibiliSubtitleMetaCacheService] 两级缓存加载。 */
@Suppress("UnusedReceiverParameter")
suspend fun BilibiliVideoSubtitleRoute.handler2(param: String): ValidJsonString {
    return try {
        val p = param.loadJsonModel<BilibiliVideoSubtitleParam>().getOrThrow()
        logger.debug("请求 bvid=${p.bvid} page=${p.pageIndex} isUpdate=${p.isUpdate}")

        val cfg = AppConfigService.repo.getConfig()

        val raw = BilibiliSubtitleMetaCacheService.fetchBilibiliSubtitleMeta(p.bvid, p.pageIndex, isUpdate = p.isUpdate, cfg)
        logger.debug("返回结果 bvid=${p.bvid} page=${p.pageIndex} raw_len=${raw.length}")
        ValidJsonString(raw)
    } catch (e: Throwable) {
        logger.warn("[BilibiliVideoSubtitleRoute] 获取字幕元信息失败", isHappensFrequently = false, err = e)
        buildJsonObject { put("error", e.message ?: "unknown") }.toValidJson()
    }
}
