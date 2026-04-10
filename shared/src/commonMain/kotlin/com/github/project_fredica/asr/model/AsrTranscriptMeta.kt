package com.github.project_fredica.asr.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 反序列化 transcript.meta.json */
@Serializable
data class AsrTranscriptMeta(
    @SerialName("model_size") val modelSize: String? = null,
    val language: String? = null,
    @SerialName("total_segments") val totalSegments: Int? = null,
    @SerialName("total_chunks") val totalChunks: Int? = null,
)
