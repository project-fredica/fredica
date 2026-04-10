package com.github.project_fredica.asr.model

import kotlinx.serialization.Serializable

/** Bilibili 字幕 body JSON 外层结构。 */
@Serializable
data class BilibiliSubtitleBodyPayload(
    val body: List<BilibiliSubtitleBodyItem> = emptyList(),
)
