package com.github.project_fredica.db

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MaterialVideo(
    val id: String,
    @SerialName("source_type") val sourceType: String,
    @SerialName("source_id") val sourceId: String,
    val title: String,
    @SerialName("cover_url") val coverUrl: String,
    val description: String,
    val duration: Int,
    @SerialName("pipeline_status") val pipelineStatus: String,
    @SerialName("local_video_path") val localVideoPath: String,
    @SerialName("local_audio_path") val localAudioPath: String,
    @SerialName("transcript_path") val transcriptPath: String,
    val extra: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    /** IDs of categories this video belongs to (populated by listAll JOIN) */
    @SerialName("category_ids") val categoryIds: List<String> = emptyList(),
)

interface MaterialVideoRepo {
    suspend fun upsertAll(videos: List<MaterialVideo>): Int
    suspend fun listAll(): List<MaterialVideo>
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
