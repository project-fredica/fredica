package com.github.project_fredica.asr.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 字幕内容响应 */
@Serializable
data class MaterialSubtitleContentResponse(
    val text: String,
    @SerialName("word_count") val wordCount: Int,
    @SerialName("segment_count") val segmentCount: Int,
    val source: String,
    @SerialName("subtitle_url") val subtitleUrl: String,
)
