package com.github.project_fredica.asr.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** ASR_SPAWN_CHUNKS 任务的 payload，由 [com.github.project_fredica.asr.material_workflow_ext.startWhisperTranscribe2] 构建。 */
@Serializable
data class AsrSpawnChunksPayload(
    @SerialName("extract_audio_task_id") val extractAudioTaskId: String,
    val language: String,
    @SerialName("model_size") val modelSize: String,
    @SerialName("output_dir") val outputDir: String,
    @SerialName("chunk_duration_sec") val chunkDurationSec: Int = 300,
    @SerialName("overlap_sec") val overlapSec: Int = 60,
    @SerialName("allow_download") val allowDownload: Boolean = false,
)
