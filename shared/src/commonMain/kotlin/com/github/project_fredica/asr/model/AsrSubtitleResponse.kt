package com.github.project_fredica.asr.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** ASR 字幕详情响应 */
@Serializable
data class AsrSubtitleResponse(
    val segments: List<AsrSubtitleSegment> = emptyList(),
    val language: String? = null,
    @SerialName("model_size") val modelSize: String? = null,
    @SerialName("segment_count") val segmentCount: Int = 0,
    @SerialName("total_chunks") val totalChunks: Int = 0,
    @SerialName("done_chunks") val doneChunks: Int = 0,
    val partial: Boolean = false,
)
