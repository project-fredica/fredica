package com.github.project_fredica.db

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Combined view: base [Material] fields plus video-specific detail.
 *
 * Internally stored across two tables:
 *  - [material]       — base fields (id, type, title, source, status, extra, …)
 *  - [material_video] — video-specific fields (duration, local paths, transcript)
 *
 * This is the shape returned by the API for video materials.
 */
@Serializable
data class MaterialVideo(
    val id: String,
    val type: String = MaterialType.VIDEO,
    val title: String,
    @SerialName("source_type")      val sourceType: String,
    @SerialName("source_id")        val sourceId: String,
    @SerialName("cover_url")        val coverUrl: String,
    val description: String,
    /** Total duration in seconds (video-specific). */
    val duration: Int,
    @SerialName("pipeline_status")  val pipelineStatus: String,
    @SerialName("local_video_path") val localVideoPath: String,
    @SerialName("local_audio_path") val localAudioPath: String,
    @SerialName("transcript_path")  val transcriptPath: String,
    /** JSON blob for source-specific metadata. */
    val extra: String,
    @SerialName("created_at")       val createdAt: Long,
    @SerialName("updated_at")       val updatedAt: Long,
    @SerialName("category_ids")     val categoryIds: List<String> = emptyList(),
)

interface MaterialVideoRepo {
    /** Upsert video materials into both [material] and [material_video] tables. Returns count of new inserts. */
    suspend fun upsertAll(videos: List<MaterialVideo>): Int
    /** Returns all video materials as a JOIN view. */
    suspend fun listAll(): List<MaterialVideo>
    /** Delete video materials and cascade to detail table, category associations, and tasks. */
    suspend fun deleteByIds(ids: List<String>)
}

object MaterialVideoService {
    private var _repo: MaterialVideoRepo? = null

    val repo: MaterialVideoRepo
        get() = _repo ?: throw IllegalStateException("MaterialVideoService not initialized")

    fun initialize(repo: MaterialVideoRepo) {
        _repo = repo
    }
}
