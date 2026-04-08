package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.BilibiliSubtitleUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.db.BilibiliSubtitleBodyCacheService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST /api/v1/MaterialSubtitleContentRoute
 *
 * 按通用素材字幕接口返回字幕全文，当前先支持 bilibili_platform。
 */
object MaterialSubtitleContentRoute : FredicaApi.Route {
    private val logger = createLogger { "MaterialSubtitleContentRoute" }

    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "获取素材字幕全文"

    override suspend fun handler(param: String): ValidJsonString {
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

            val response = when (p.source) {
                "", "bilibili_platform" -> buildBilibiliSubtitleContentResponse(p)
                "asr" -> buildAsrSubtitleContentResponse(p)
                else -> {
                    logger.debug("MaterialSubtitleContentRoute: 暂不支持的字幕来源 source=${p.source}，返回空正文")
                    MaterialSubtitleContentResponse(
                        text = "",
                        wordCount = 0,
                        segmentCount = 0,
                        source = p.source,
                        subtitleUrl = p.subtitleUrl,
                    )
                }
            }
            logger.info(
                "MaterialSubtitleContentRoute: 返回字幕正文 source=${response.source}" +
                    " wordCount=${response.wordCount} segmentCount=${response.segmentCount}"
            )
            AppUtil.dumpJsonStr(response).getOrThrow()
        } catch (e: Throwable) {
            logger.warn("[MaterialSubtitleContentRoute] 获取素材字幕正文失败", isHappensFrequently = false, err = e)
            buildValidJson { kv("error", e.message ?: "unknown") }
        }
    }

    private suspend fun buildAsrSubtitleContentResponse(
        param: MaterialSubtitleContentParam,
    ): MaterialSubtitleContentResponse {
        // subtitleUrl holds the absolute path to transcript.srt
        val srtFile = java.io.File(param.subtitleUrl)
        val text = if (srtFile.exists()) srtFile.readText() else ""
        val lines = text.lines().filter { it.isNotEmpty() }
        return MaterialSubtitleContentResponse(
            text = text,
            wordCount = text.length,
            segmentCount = lines.size,
            source = "asr",
            subtitleUrl = param.subtitleUrl,
        )
    }

    private suspend fun buildBilibiliSubtitleContentResponse(
        param: MaterialSubtitleContentParam,
    ): MaterialSubtitleContentResponse {
        val raw = BilibiliSubtitleBodyCacheService.fetchBilibiliSubtitleBody(
            subtitleUrlFieldValue = param.subtitleUrl,
            isUpdate = param.isUpdate,
        )
        val text = BilibiliSubtitleUtil.parseSubtitleBodyText(raw) ?: ""
        val lines = text.lines().filter { it.isNotEmpty() }
        return MaterialSubtitleContentResponse(
            text = text,
            wordCount = text.length,
            segmentCount = lines.size,
            source = "bilibili_platform",
            subtitleUrl = param.subtitleUrl,
        )
    }
}

@Serializable
data class MaterialSubtitleContentParam(
    @SerialName("subtitle_url") val subtitleUrl: String,
    val source: String = "bilibili_platform",
    @SerialName("is_update") val isUpdate: Boolean = false,
)

@Serializable
data class MaterialSubtitleContentResponse(
    val text: String,
    @SerialName("word_count") val wordCount: Int,
    @SerialName("segment_count") val segmentCount: Int,
    val source: String,
    @SerialName("subtitle_url") val subtitleUrl: String,
)
