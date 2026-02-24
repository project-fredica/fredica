package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createJson
import com.github.project_fredica.db.MaterialCategoryService
import com.github.project_fredica.db.MaterialVideo
import com.github.project_fredica.db.MaterialVideoService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * Builds the deterministic DB primary key for a bilibili video.
 *
 * Format: `bilibili_bvid__{bvid}__P{page}`
 * Example: `bilibili_bvid__BV1NK4y1V7M5__P1`
 *
 * When importing from a favorites list the entry represents the whole video
 * (page 1 of potentially many). Multi-page imports use distinct page numbers.
 */
fun bilibiliVideoId(bvid: String, page: Int = 1): String =
    "bilibili_bvid__${bvid}__P$page"

object MaterialImportRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "将浏览到的视频元数据导入素材库"

    override suspend fun handler(param: String): ValidJsonString {
        val p = lenientJson.decodeFromString<MaterialImportParam>(param)

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
                    kv("page_count", video.page)
                    kv("bvid", video.bvid)
                }
            }.toString()

            MaterialVideo(
                // Deterministic ID — no UUID, survives repeated imports
                id = bilibiliVideoId(video.bvid),
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
            val videoIds = p.videos.map { bilibiliVideoId(it.bvid) }
            MaterialCategoryService.repo.linkVideos(videoIds, p.categoryIds)
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
