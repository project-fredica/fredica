package com.github.project_fredica.asr.service

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.asr.model.BilibiliMaterialExtra
import com.github.project_fredica.asr.model.BilibiliSubtitleBodyPayload
import com.github.project_fredica.asr.model.BilibiliSubtitleMeta
import com.github.project_fredica.db.MaterialMediaSpec
import com.github.project_fredica.db.MaterialVideoService

/**
 * Bilibili 字幕工具类。
 *
 * 提供字幕元信息解析、URL 稳定化（去除过期 query 参数）、字幕 body 全文提取、
 * 以及从本地缓存快速获取字幕文本等静态方法。
 */
object BilibiliSubtitleUtil {
    private val logger = createLogger()
    fun parseMateRaw(metaRaw: String): Result<BilibiliSubtitleMeta> =
        metaRaw.loadJsonModel<BilibiliSubtitleMeta>()

    /**
     * 去掉 auth_key / wts 等会过期的 query 参数，保留稳定的路径+其余参数作为缓存 key。
     * 被 [BilibiliSubtitleBodyCacheService] 及字幕缓存查询共用。
     */
    fun extractSubtitleUrlKey(url: String): String {
        val normalized = if (url.startsWith("//")) "https:$url" else url
        return try {
            val uri = java.net.URI(normalized)
            val stableParams = (uri.rawQuery ?: "")
                .split("&")
                .filter { part ->
                    val key = part.substringBefore("=")
                    key !in setOf("auth_key", "wts", "w_rid", "e")
                }
                .joinToString("&")
            "${uri.host}${uri.rawPath ?: ""}${if (stableParams.isNotEmpty()) "?$stableParams" else ""}"
        } catch (err: Exception) {
            logger.warn("error on extract subtitle url key", isHappensFrequently = false, err)
            normalized
        }
    }

    /**
     * 从字幕元信息 rawResult 中提取匹配 [language] 的字幕 URL（优先 subtitle_url）。
     * - [language] 为 null 时取第一条。
     * - [language] 不为 null 时按 `lan` 字段严格匹配；无匹配返回 null。
     */
    fun extractFirstSubtitleUrl(metaRaw: String, language: String? = null): String? {
        val meta = parseMateRaw(metaRaw).getOrNull() ?: return null
        val subtitles = meta.subtitles ?: return null
        val item = if (language != null) {
            subtitles.firstOrNull { it.lan == language }
        } else {
            subtitles.firstOrNull()
        }
        return item?.let { it.subtitleUrl ?: it.subtitleUrlV2 }
    }

    /**
     * 从字幕 body rawResult 中提取拼接后的全文（按近似 SRT 段落输出）。
     * body 为空或解析失败时返回 null。
     */
    fun parseSubtitleBodyText(bodyRaw: String): String? {
        val payload = bodyRaw.loadJsonModel<BilibiliSubtitleBodyPayload>().getOrNull() ?: return null
        val lines = payload.body.mapIndexedNotNull { index, item ->
            val (seq, timeRange, text) = item.toSrtLines(index + 1)
            text.takeIf { it.isNotEmpty() }?.let { "$seq\n$timeRange\n$it" }
        }
        return if (lines.isEmpty()) null else lines.joinToString("\n\n")
    }

    /**
     * 从本地缓存获取指定素材的字幕全文，不触发任何网络请求。
     * 任一步骤缓存未命中则返回 null。
     *
     * @param language 语言代码（如 `"ai-zh"`、`"zh"`），null 表示取第一条。
     *
     * 支持来源：bilibili（从 material.extra.bvid + 字幕 meta/body 缓存读取）
     */
    suspend fun fetchSubtitleTextFromCache(materialId: String, language: String? = null): String? {
        val material = MaterialVideoService.repo.findById(materialId)
        if (material == null) {
            logger.debug("fetchSubtitleTextFromCache: material not found id=$materialId")
            return null
        }
        if (!MaterialMediaSpec.isBilibiliVideo(material.type, material.sourceType)) {
            logger.debug("fetchSubtitleTextFromCache: not bilibili video id=$materialId type=${material.type} sourceType=${material.sourceType}")
            return null
        }

        val bvid = material.extra.loadJsonModel<BilibiliMaterialExtra>()
            .getOrNull()?.bvid?.takeIf { it.isNotBlank() }
        if (bvid == null) {
            logger.debug("fetchSubtitleTextFromCache: no bvid in extra id=$materialId")
            return null
        }

        val metaCache = BilibiliSubtitleMetaCacheService.repo.queryBest(bvid, pageIndex = 0)
        if (metaCache == null) {
            logger.debug("fetchSubtitleTextFromCache: no meta cache bvid=$bvid")
            return null
        }
        val subtitleUrl = extractFirstSubtitleUrl(metaCache.rawResult, language)
        if (subtitleUrl == null) {
            logger.debug("fetchSubtitleTextFromCache: no subtitle url bvid=$bvid language=$language")
            return null
        }

        val urlKey = extractSubtitleUrlKey(subtitleUrl)
        val bodyCache = BilibiliSubtitleBodyCacheService.repo.queryBest(urlKey)
        if (bodyCache == null) {
            logger.debug("fetchSubtitleTextFromCache: no body cache bvid=$bvid urlKey=$urlKey")
            return null
        }

        val text = parseSubtitleBodyText(bodyCache.rawResult)
        logger.debug("fetchSubtitleTextFromCache: bvid=$bvid language=$language textLen=${text?.length ?: 0}")
        return text
    }
}
