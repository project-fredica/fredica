package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createJson
import com.github.project_fredica.db.MaterialCategoryService
import com.github.project_fredica.db.MaterialType
import com.github.project_fredica.db.MaterialVideo
import com.github.project_fredica.db.MaterialVideoService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * Builds the deterministic DB primary key for a bilibili video page.
 *
 * Format: `bilibili_bvid__{bvid}__P{page}`
 * Example: `bilibili_bvid__BV1NK4y1V7M5__P1`
 *
 * `page` is the actual 1-based page number of this item.
 * Favourites-list imports always pass `page = 1` (the representative entry).
 */
fun bilibiliVideoId(bvid: String, page: Int = 1): String =
    "bilibili_bvid__${bvid}__P$page"

object MaterialImportRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "将浏览到的视频元数据导入素材库"

    override suspend fun handler(param: String): ValidJsonString {
        val p = lenientJson.decodeFromString<MaterialImportParam>(param)
        require(p.categoryIds.isNotEmpty()) { "至少需要指定一个分类" }

        val nowSec = System.currentTimeMillis() / 1000L

        val materials = p.videos.map { video ->
            val extraJson = createJson {
                obj {
                    kv("upper_name", video.upper.name)
                    kv("upper_face_url", video.upper.face)
                    kv("upper_mid", video.upper.mid)
                    kv("cnt_play", video.cntInfo.play)
                    kv("cnt_collect", video.cntInfo.collect)
                    kv("cnt_danmaku", video.cntInfo.danmaku)
                    kv("fav_time", video.favTime)
                    kv("source_fid", p.sourceFid)
                    kv("bvid", video.bvid)
                }
            }.toString()

            MaterialVideo(
                id = bilibiliVideoId(video.bvid, video.page),
                type = MaterialType.VIDEO,
                sourceType = p.sourceType,
                sourceId = video.bvid,
                title = video.title,
                coverUrl = video.cover,
                description = video.intro,
                duration = video.duration,
                pipelineStatus = "pending",
                localVideoPath = "",
                localAudioPath = "",
                transcriptPath = "",
                extra = extraJson,
                createdAt = nowSec,
                updatedAt = nowSec,
            )
        }

        val inserted = MaterialVideoService.repo.upsertAll(materials)

        // Link to categories if any were provided.
        // IDs are deterministic so we can compute them directly — no extra DB query needed.
        if (p.categoryIds.isNotEmpty()) {
            val materialIds = p.videos.map { bilibiliVideoId(it.bvid, it.page) }
            MaterialCategoryService.repo.linkMaterials(materialIds, p.categoryIds)
        }

        return ValidJsonString("""{"inserted":$inserted,"total":${materials.size}}""")
    }
}

@Serializable
data class MaterialImportParam(
    @SerialName("source_type") val sourceType: String,
    @SerialName("source_fid") val sourceFid: String = "",
    val videos: List<BilibiliMediaImportItem>,
    @SerialName("category_ids") val categoryIds: List<String> = emptyList(),
)

@Serializable
data class BilibiliMediaImportItem(
    val id: Long = 0L,
    val title: String = "",
    val cover: String = "",
    val intro: String = "",
    /** Actual 1-based page number this item represents (default 1). */
    val page: Int = 1,
    val duration: Int = 0,
    val upper: BilibiliUpperImportInfo = BilibiliUpperImportInfo(),
    @SerialName("cnt_info") val cntInfo: BilibiliCntImportInfo = BilibiliCntImportInfo(),
    @SerialName("fav_time") val favTime: Long = 0L,
    val bvid: String = "",
)

@Serializable
data class BilibiliUpperImportInfo(
    val mid: Long = 0L,
    val name: String = "",
    val face: String = "",
)

@Serializable
data class BilibiliCntImportInfo(
    val collect: Int = 0,
    val play: Int = 0,
    val danmaku: Int = 0,
)
