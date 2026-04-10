package com.github.project_fredica.asr.route_ext

import com.github.project_fredica.api.routes.MaterialSubtitleContentRoute
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.asr.service.MaterialSubtitleService
import com.github.project_fredica.asr.model.MaterialSubtitleContentParam
import com.github.project_fredica.asr.model.MaterialSubtitleContentResponse
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val logger = createLogger { "MaterialSubtitleContentRoute" }

/** 获取字幕全文内容，按 source 分发到 bilibili 或 ASR 读取。 */
@Suppress("UnusedReceiverParameter")
suspend fun MaterialSubtitleContentRoute.handler2(param: String): ValidJsonString {
    return try {
        val p = param.loadJsonModel<MaterialSubtitleContentParam>().getOrThrow()
        logger.debug(
            "MaterialSubtitleContentRoute: source=${p.source} isUpdate=${p.isUpdate}" +
                " subtitleUrl=${p.subtitleUrl.take(120)}"
        )
        if (p.subtitleUrl.isBlank()) {
            logger.debug("MaterialSubtitleContentRoute: subtitleUrl 为空，返回空正文")
            return AppUtil.dumpJsonStr(
                MaterialSubtitleContentResponse(
                    text = "",
                    wordCount = 0,
                    segmentCount = 0,
                    source = p.source,
                    subtitleUrl = p.subtitleUrl,
                )
            ).getOrThrow()
        }

        val response = MaterialSubtitleService.fetchSubtitleContent(p.source, p.subtitleUrl, p.isUpdate)
        logger.info(
            "MaterialSubtitleContentRoute: 返回字幕正文 source=${response.source}" +
                " wordCount=${response.wordCount} segmentCount=${response.segmentCount}"
        )
        AppUtil.dumpJsonStr(response).getOrThrow()
    } catch (e: Throwable) {
        logger.warn("[MaterialSubtitleContentRoute] 获取素材字幕正文失败", isHappensFrequently = false, err = e)
        buildJsonObject { put("error", e.message ?: "unknown") }.toValidJson()
    }
}
