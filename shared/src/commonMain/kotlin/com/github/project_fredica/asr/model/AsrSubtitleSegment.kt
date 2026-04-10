package com.github.project_fredica.asr.model

import kotlinx.serialization.Serializable

/** ASR 字幕分段 */
@Serializable
data class AsrSubtitleSegment(
    val from: Double,
    val to: Double,
    val content: String,
)
