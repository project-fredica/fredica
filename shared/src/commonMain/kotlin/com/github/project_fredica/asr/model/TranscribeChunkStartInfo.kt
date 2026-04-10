package com.github.project_fredica.asr.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** chunk 转录开始时写入的 start_info.json 内容。 */
@Serializable
data class TranscribeChunkStartInfo(
    @SerialName("started_at") val startedAt: String,
    @SerialName("audio_path") val audioPath: String,
    @SerialName("model_size") val modelSize: String,
    val language: String?,
    @SerialName("compute_type") val computeType: String,
)
