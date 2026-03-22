package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
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
 * 不触发任何网络请求，仅读取本地 bilibili_subtitle_meta_cache 缓存。
 *
 * 目前支持：
 *   - bilibili 来源：从 bilibili_subtitle_meta_cache 读取 page_index=0 的最新成功结果
 * 其他来源暂返回空列表。
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

        if (material.sourceType != "bilibili") {
            return AppUtil.dumpJsonStr(emptyList<MaterialSubtitleItem>()).getOrThrow()
        }

        // 从 extra JSON 中提取 bvid
        val extra = material.extra.loadJsonModel<BilibiliMaterialExtra>().getOrNull()
        val bvid = extra?.bvid
        if (bvid.isNullOrBlank()) {
            logger.warn("materialId=$materialId source=bilibili 但 extra.bvid 为空")
            return AppUtil.dumpJsonStr(emptyList<MaterialSubtitleItem>()).getOrThrow()
        }

        // 查询 page_index=0 的最新成功缓存
        val cached = BilibiliSubtitleMetaCacheService.repo.queryBest(bvid, 0)
            ?: return AppUtil.dumpJsonStr(emptyList<MaterialSubtitleItem>()).getOrThrow()

        val meta = BilibiliSubtitleUtil.parseMateRaw(cached.rawResult).getOrNull()
        val items: List<MaterialSubtitleItem> = meta?.subtitles?.mapNotNull { item ->
            // 优先使用 subtitle_url_v2（更稳定），回退到 subtitle_url
            val url = item.subtitleUrlV2 ?: item.subtitleUrl ?: return@mapNotNull null
            val lan = item.lan ?: return@mapNotNull null
            MaterialSubtitleItem(
                lan = lan,
                lanDoc = item.lanDoc ?: lan,
                source = "bilibili_platform",
                queriedAt = cached.queriedAt,
                subtitleUrl = url,
                type = item.type ?: 0,
            )
        } ?: emptyList()

        logger.debug("materialId=$materialId bvid=$bvid 缓存字幕数=${items.size}")
        return AppUtil.dumpJsonStr(items).getOrThrow()
    }
}

@Serializable
data class MaterialSubtitleItem(
    val lan: String,
    @SerialName("lan_doc") val lanDoc: String,
    /** 字幕来源标识，如 "bilibili_platform" */
    val source: String,
    /** 缓存写入时间（Unix 秒） */
    @SerialName("queried_at") val queriedAt: Long,
    /** 字幕内容 URL，用于后续调用 BilibiliVideoSubtitleBodyRoute 获取正文 */
    @SerialName("subtitle_url") val subtitleUrl: String,
    /** 0=人工字幕，1=AI 字幕 */
    val type: Int = 0,
)

/** 仅用于从 Material.extra JSON 中提取 bvid 字段，忽略其他字段 */
@Serializable
internal data class BilibiliMaterialExtra(
    val bvid: String? = null,
)
