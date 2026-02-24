package com.github.project_fredica.db

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── Material Video ───────────────────────────────────────────────────────────

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

// ─── Material Category ────────────────────────────────────────────────────────

@Serializable
data class MaterialCategory(
    val id: String,
    val name: String,
    val description: String,
    @SerialName("video_count") val videoCount: Int = 0,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
)

interface MaterialCategoryRepo {
    /** List all categories with their video counts */
    suspend fun listAll(): List<MaterialCategory>
    /** Create category if name does not exist, otherwise return existing one */
    suspend fun createOrGet(name: String, description: String = ""): MaterialCategory
    /** Delete category and all its junction entries */
    suspend fun deleteById(id: String)
    /** Link video DB IDs to category IDs in the junction table */
    suspend fun linkVideos(videoIds: List<String>, categoryIds: List<String>)
    /** Replace all category associations for a single video */
    suspend fun setVideoCategories(videoId: String, categoryIds: List<String>)
}

object MaterialCategoryService {
    private var _repo: MaterialCategoryRepo? = null

    val repo: MaterialCategoryRepo
        get() = _repo ?: throw IllegalStateException("MaterialCategoryService not initialized")

    fun initialize(repo: MaterialCategoryRepo) {
        _repo = repo
    }
}

// ─── App Config ───────────────────────────────────────────────────────────────

@Serializable
data class AppConfig(
    @SerialName("server_port") val serverPort: Int = 7631,
    @SerialName("data_dir") val dataDir: String = "",
    @SerialName("auto_start") val autoStart: Boolean = false,
    @SerialName("start_minimized") val startMinimized: Boolean = false,
    @SerialName("open_browser_on_start") val openBrowserOnStart: Boolean = true,
    val theme: String = "system",
    val language: String = "zh-CN",
    @SerialName("proxy_enabled") val proxyEnabled: Boolean = false,
    @SerialName("proxy_url") val proxyUrl: String = "",
    @SerialName("rsshub_url") val rsshubUrl: String = "",
)

interface AppConfigRepo {
    suspend fun getConfig(): AppConfig
    suspend fun updateConfig(config: AppConfig)
}

object AppConfigService {
    private var _repo: AppConfigRepo? = null

    val repo: AppConfigRepo
        get() = _repo ?: throw IllegalStateException("AppConfigService not initialized")

    fun initialize(repo: AppConfigRepo) {
        _repo = repo
    }
}
