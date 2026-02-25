package com.github.project_fredica.db

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a single processing task for any [Material] (video, audio, image, article, …).
 *
 * Task types (task_type values):
 *  - download            : Download the source file from the origin platform
 *  - split_120s          : Split into 120-second segments
 *  - vocal_separation    : Separate vocals from background (requires download)
 *  - speaker_diarization : Speaker diarization / clustering (requires vocal_separation)
 *  - tongyi_tingwu       : Tongyi Tingwu ASR (requires download)
 *  - faster_whisper      : faster-whisper ASR (requires download)
 *
 * Status flow: queued → running → done / failed
 *
 * A task may only enter `running` once all tasks listed in [dependsOn] have status `done`.
 * [dependsOn] is a JSON array of prerequisite task UUIDs stored as a plain TEXT column.
 */
@Serializable
data class MaterialTask(
    val id: String,
    /** FK → material.id — works for any material type. */
    @SerialName("material_id")  val materialId: String,
    @SerialName("task_type")    val taskType: String,
    val status: String = "queued",
    /** JSON array of prerequisite task UUIDs, e.g. `["uuid1","uuid2"]`. */
    @SerialName("depends_on")   val dependsOn: String = "[]",
    @SerialName("input_path")   val inputPath: String = "",
    @SerialName("output_path")  val outputPath: String = "",
    @SerialName("error_msg")    val errorMsg: String = "",
    @SerialName("created_at")   val createdAt: Long,
    @SerialName("updated_at")   val updatedAt: Long,
)

interface MaterialTaskRepo {
    suspend fun create(task: MaterialTask)
    suspend fun createAll(tasks: List<MaterialTask>)
    suspend fun listByMaterialId(materialId: String): List<MaterialTask>
    suspend fun updateStatus(id: String, status: String, errorMsg: String = "", outputPath: String = "")
}

object MaterialTaskService {
    private var _repo: MaterialTaskRepo? = null

    val repo: MaterialTaskRepo
        get() = _repo ?: throw IllegalStateException("MaterialTaskService not initialized")

    fun initialize(repo: MaterialTaskRepo) {
        _repo = repo
    }
}
