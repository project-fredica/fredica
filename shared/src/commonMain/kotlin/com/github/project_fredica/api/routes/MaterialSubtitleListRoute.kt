package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.BilibiliMaterialExtra
import com.github.project_fredica.apputil.BilibiliSubtitleUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.BilibiliSubtitleMetaCacheService
import com.github.project_fredica.db.MaterialVideoService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GET /api/v1/MaterialSubtitleListRoute?material_id=xxx
 *
 * 查询素材已缓存的字幕列表。
 * 不触发任何网络请求，仅读取本地缓存与落盘文件。
 *
 * 目前支持：
 *   - bilibili 来源：从 bilibili_subtitle_meta_cache 读取 page_index=0 的最新成功结果
 *   - 所有来源：扫描 asr_result/transcript.srt 是否存在，存在则追加 source=asr 条目
 *
 * 返回：List<MaterialSubtitleItem>（JSON 数组）
 */
object MaterialSubtitleListRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "查询素材已缓存的字幕列表"

    private val logger = createLogger { "MaterialSubtitleListRoute" }

    override suspend fun handler(param: String): ValidJsonString {
        val query = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val materialId = query["material_id"]?.firstOrNull()
            ?: return AppUtil.dumpJsonStr(emptyList<MaterialSubtitleItem>()).getOrThrow()

        val material = MaterialVideoService.repo.findById(materialId)
            ?: return AppUtil.dumpJsonStr(emptyList<MaterialSubtitleItem>()).getOrThrow()

        val items = mutableListOf<MaterialSubtitleItem>()

        // ── Bilibili 平台字幕 ──────────────────────────────────────────────────
        if (material.sourceType == "bilibili") {
            val extra = material.extra.loadJsonModel<BilibiliMaterialExtra>().getOrNull()
            val bvid = extra?.bvid
            if (!bvid.isNullOrBlank()) {
                val cached = BilibiliSubtitleMetaCacheService.repo.queryBest(bvid, 0)
                if (cached != null) {
                    val meta = BilibiliSubtitleUtil.parseMateRaw(cached.rawResult).getOrNull()
                    meta?.subtitles?.mapNotNull { item ->
                        val url = item.subtitleUrl ?: item.subtitleUrlV2 ?: return@mapNotNull null
                        val lan = item.lan ?: return@mapNotNull null
                        MaterialSubtitleItem(
                            lan = lan,
                            lanDoc = item.lanDoc ?: lan,
                            source = "bilibili_platform",
                            queriedAt = cached.queriedAt,
                            subtitleUrl = url,
                            type = item.type ?: 0,
                        )
                    }?.let { items.addAll(it) }
                }
            } else {
                logger.warn("materialId=$materialId source=bilibili 但 extra.bvid 为空")
            }
        }

        // ── ASR 结果（transcript.srt 落盘检测）────────────────────────────────
        val mediaDir = AppUtil.Paths.materialMediaDir(materialId)
        val srtFile = mediaDir.resolve("asr_result").resolve("transcript.srt")
        if (srtFile.exists() && srtFile.length() > 0) {
            items.add(
                MaterialSubtitleItem(
                    lan = "asr",
                    lanDoc = "ASR 识别",
                    source = "asr",
                    queriedAt = srtFile.lastModified() / 1000,
                    subtitleUrl = srtFile.absolutePath,
                    type = 1,
                )
            )
        }

        logger.debug("materialId=$materialId 字幕数=${items.size}")
        return AppUtil.dumpJsonStr(items).getOrThrow()
    }
}

@Serializable
data class MaterialSubtitleItem(
    val lan: String,
    @SerialName("lan_doc") val lanDoc: String,
    /** 字幕来源标识，如 "bilibili_platform" / "asr" */
    val source: String,
    /** 缓存写入时间（Unix 秒） */
    @SerialName("queried_at") val queriedAt: Long,
    /** 字幕内容 URL 或本地文件路径 */
    @SerialName("subtitle_url") val subtitleUrl: String,
    /** 0=人工字幕，1=AI 字幕 */
    val type: Int = 0,
)
