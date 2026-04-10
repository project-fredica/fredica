package com.github.project_fredica.asr.model

import kotlinx.serialization.Serializable

/** Bilibili 字幕 body 中的单条字幕行（含时间范围和文本）。 */
@Serializable
data class BilibiliSubtitleBodyItem(
    val content: String,
    val from: Double,
    val to: Double,
) {
    fun toSrtLines(index: Int): Triple<Int, String, String> = Triple(
        index,
        "${from.toSrtTimestamp()} --> ${to.toSrtTimestamp()}",
        content.trim(),
    )
}
