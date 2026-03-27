package com.github.project_fredica.apputil

import com.github.project_fredica.db.BilibiliSubtitleBodyCacheService
import com.github.project_fredica.db.BilibiliSubtitleMetaCacheService
import com.github.project_fredica.db.MaterialVideoService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface BilibiliApiPythonCredentialConfig {
    val bilibiliSessdata: String
    val bilibiliBiliJct: String
    val bilibiliBuvid3: String
    val bilibiliDedeuserid: String
    val bilibiliAcTimeValue: String
    val bilibiliBuvid4: String
    val bilibiliProxy: String
}

// ── Bilibili 元信息 ───────────────────────────────────────────────────────────

@Serializable
data class BilibiliSubtitleMeta(
    val code: Int?,
    val message: String?,
    @SerialName("allow_submit")
    val allowSubmit: Boolean?,
    val subtitles: List<BilibiliSubtitleMetaSubtitleItem>?
)

@Serializable
data class BilibiliSubtitleMetaSubtitleItem(
    val id: Long?,
    val lan: String?,
    @SerialName("lan_doc")
    val lanDoc: String?,
    @SerialName("is_lock")
    val isLock: Boolean?,
    @SerialName("subtitle_url")
    val subtitleUrl: String?,
    @SerialName("subtitle_url_v2")
    val subtitleUrlV2: String?,
    val type: Int?,
    @SerialName("id_str")
    val idStr: String?,
    @SerialName("ai_type")
    val aiType: Int?,
    @SerialName("ai_status")
    val aiStatus: Int?,
)

// ── 素材扩展字段 ──────────────────────────────────────────────────────────────

/** 从 Material.extra JSON 中提取 bvid 字段，忽略其他字段。 */
@Serializable
data class BilibiliMaterialExtra(
    val bvid: String? = null,
)

// ── 字幕 Body ─────────────────────────────────────────────────────────────────

/** Bilibili 字幕 body JSON 外层结构。 */
@Serializable
data class BilibiliSubtitleBodyPayload(
    val body: List<BilibiliSubtitleBodyItem> = emptyList(),
)

@Serializable
data class BilibiliSubtitleBodyItem(
    val content: String,
    val from: Double,
    val to: Double,
) {
    fun toSrtLines(index: Int): Triple<Int, String, String> = Triple(
        index,
        "${from.toSrtTimestamp()} --> ${to.toSrtTimestamp()}",
        content.trim(),
    )
}

private fun Double.toSrtTimestamp(): String {
    val totalMillis = (this * 1000).toLong().coerceAtLeast(0L)
    val hours = totalMillis / 3_600_000
    val minutes = (totalMillis % 3_600_000) / 60_000
    val seconds = (totalMillis % 60_000) / 1000
    val millis = totalMillis % 1000
    return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
}

// ── BilibiliSubtitleUtil ──────────────────────────────────────────────────────

object BilibiliSubtitleUtil {

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
        } catch (_: Exception) {
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
        val material = MaterialVideoService.repo.findById(materialId) ?: return null
        if (material.sourceType != "bilibili") return null

        val bvid = material.extra.loadJsonModel<BilibiliMaterialExtra>()
            .getOrNull()?.bvid?.takeIf { it.isNotBlank() } ?: return null

        val metaCache = BilibiliSubtitleMetaCacheService.repo.queryBest(bvid, pageIndex = 0)
            ?: return null
        val subtitleUrl = extractFirstSubtitleUrl(metaCache.rawResult, language) ?: return null

        val urlKey = extractSubtitleUrlKey(subtitleUrl)
        val bodyCache = BilibiliSubtitleBodyCacheService.repo.queryBest(urlKey) ?: return null

        return parseSubtitleBodyText(bodyCache.rawResult)
    }
}
