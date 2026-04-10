package com.github.project_fredica.asr.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** chunk 转录完成后写入的 chunk_XXXX.meta.json 内容。 */
@Serializable
data class TranscribeChunkMeta(
    val language: String,
    @SerialName("model_size") val modelSize: String,
    @SerialName("segment_count") val segmentCount: Int,
    @SerialName("completed_at") val completedAt: String,
    val partial: Boolean = false,
    @SerialName("core_start_sec") val coreStartSec: Double? = null,
    @SerialName("core_end_sec") val coreEndSec: Double? = null,
)
