package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.auth.AuthRole
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.github.project_fredica.apputil.bilibiliVideoId
import com.github.project_fredica.material_category.service.MaterialCategoryService
import com.github.project_fredica.db.MaterialType
import com.github.project_fredica.db.MaterialVideo
import com.github.project_fredica.db.MaterialVideoService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val lenientJson = Json { ignoreUnknownKeys = true }

object MaterialImportRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.TENANT
    override val desc = "将浏览到的视频元数据导入素材库"

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val p = lenientJson.decodeFromString<MaterialImportParam>(param)
        require(p.categoryIds.isNotEmpty()) { "至少需要指定一个分类" }

        val nowSec = System.currentTimeMillis() / 1000L

        val materials = p.videos.map { video ->
            val extraJson = buildJsonObject {
                put("upper_name", video.upper.name)
                put("upper_face_url", video.upper.face)
                put("upper_mid", video.upper.mid)
                put("cnt_play", video.cntInfo.play)
                put("cnt_collect", video.cntInfo.collect)
                put("cnt_danmaku", video.cntInfo.danmaku)
                put("fav_time", video.favTime)
                put("source_fid", p.sourceFid)
                put("bvid", video.bvid)
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

        return buildJsonObject {
            put("inserted", inserted)
            put("total", materials.size)
        }.toValidJson()
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
