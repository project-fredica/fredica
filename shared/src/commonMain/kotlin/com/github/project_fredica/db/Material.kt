package com.github.project_fredica.db

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Base material entity — fields common to every material type.
 *
 * Each material type has a dedicated detail table:
 *  - material_video   (type = [MaterialType.VIDEO])
 *  - material_audio   (type = [MaterialType.AUDIO])
 *  - material_image   (type = [MaterialType.IMAGE])
 *  - material_article (type = [MaterialType.ARTICLE])
 *  - material_paper   (type = [MaterialType.PAPER])
 *
 * Category associations are stored in [material_category_rel] and work for any type.
 * Processing tasks are stored in [material_task] and reference [Material.id] directly.
 */
@Serializable
data class Material(
    val id: String,
    /** Discriminator — see [MaterialType] constants. */
    val type: String,
    val title: String,
    @SerialName("source_type")     val sourceType: String,
    @SerialName("source_id")       val sourceId: String,
    @SerialName("cover_url")       val coverUrl: String,
    val description: String,
    /**
     * File-completeness status (not AI-processing status):
     *  - pending     : metadata only, no local file
     *  - local_ready : local file downloaded and verified
     *  - local_error : download failed or file corrupted (retryable)
     */
    @SerialName("pipeline_status") val pipelineStatus: String,
    /** JSON blob for source-specific metadata (e.g. bilibili UP name, play count). */
    val extra: String,
    @SerialName("created_at")      val createdAt: Long,
    @SerialName("updated_at")      val updatedAt: Long,
    @SerialName("category_ids")    val categoryIds: List<String> = emptyList(),
)

/** Canonical string values for [Material.type]. */
object MaterialType {
    const val VIDEO   = "video"
    const val AUDIO   = "audio"
    const val IMAGE   = "image"
    const val ARTICLE = "article"
    const val PAPER   = "paper"
}

interface MaterialRepo {
    suspend fun listAll(): List<Material>
    suspend fun deleteByIds(ids: List<String>)
}

object MaterialService {
    private var _repo: MaterialRepo? = null

    val repo: MaterialRepo
        get() = _repo ?: throw IllegalStateException("MaterialService not initialized")

    fun initialize(repo: MaterialRepo) {
        _repo = repo
    }
}
