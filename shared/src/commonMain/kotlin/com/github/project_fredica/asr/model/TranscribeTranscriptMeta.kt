package com.github.project_fredica.asr.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 全量转录完成后写入的 transcript.meta.json 内容。 */
@Serializable
data class TranscribeTranscriptMeta(
    @SerialName("model_size") val modelSize: String,
    val language: String,
    @SerialName("total_segments") val totalSegments: Int,
    @SerialName("total_chunks") val totalChunks: Int,
    @SerialName("completed_at") val completedAt: String,
)
