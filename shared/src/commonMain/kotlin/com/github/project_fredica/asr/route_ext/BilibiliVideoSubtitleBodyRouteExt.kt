package com.github.project_fredica.asr.route_ext

import com.github.project_fredica.api.routes.BilibiliVideoSubtitleBodyRoute
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.api.routes.BilibiliVideoSubtitleBodyParam
import com.github.project_fredica.asr.service.BilibiliSubtitleBodyCacheService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val logger = createLogger { "BilibiliVideoSubtitleBodyRoute" }

/** 获取 Bilibili 字幕 body（JSON 全文），通过 [BilibiliSubtitleBodyCacheService] 两级缓存加载。 */
@Suppress("UnusedReceiverParameter")
suspend fun BilibiliVideoSubtitleBodyRoute.handler2(param: String): ValidJsonString {
    return try {
        val p = param.loadJsonModel<BilibiliVideoSubtitleBodyParam>().getOrThrow()
        logger.debug("请求字幕 body subtitleUrl=${p.subtitleUrl.take(120)} isUpdate=${p.isUpdate}")
        val raw = BilibiliSubtitleBodyCacheService.fetchBilibiliSubtitleBody(p.subtitleUrl, isUpdate = p.isUpdate)
        logger.debug("返回字幕 body raw_len=${raw.length} raw_preview=${raw.take(200)}")

        val errorField = runCatching {
            val obj = raw.loadJson().getOrThrow() as? JsonObject
            obj?.get("error")?.let { (it as? JsonPrimitive)?.content }
        }.getOrNull()
        if (errorField != null) {
            logger.warn("Python 链路返回 error subtitleUrl=${p.subtitleUrl.take(120)}: $errorField")
        }

        ValidJsonString(raw)
    } catch (e: Throwable) {
        logger.warn("[BilibiliVideoSubtitleBodyRoute] 获取字幕 body 失败", isHappensFrequently = false, err = e)
        buildJsonObject { put("error", e.message ?: "unknown") }.toValidJson()
    }
}
