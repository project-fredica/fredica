package com.github.project_fredica.asr.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** TRANSCRIBE 任务的 payload，每个音频 chunk 一个任务。 */
@Serializable
data class TranscribePayload(
    @SerialName("audio_path") val audioPath: String,
    val language: String? = null,
    @SerialName("model_size") val modelSize: String? = null,
    @SerialName("output_dir") val outputDir: String = "",
    @SerialName("chunk_index") val chunkIndex: Int = 0,
    @SerialName("total_chunks") val totalChunks: Int = 1,
    @SerialName("chunk_offset_sec") val chunkOffsetSec: Double = 0.0,
    @SerialName("core_start_sec") val coreStartSec: Double? = null,
    @SerialName("core_end_sec") val coreEndSec: Double? = null,
    @SerialName("allow_download") val allowDownload: Boolean = false,
    // deprecated: 兼容旧 payload，优先使用 output_dir
    @SerialName("output_path") val outputPath: String? = null,
)
