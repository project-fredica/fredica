package com.github.project_fredica.asr.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Bilibili 字幕元信息 API 响应（含字幕轨列表）。 */
@Serializable
data class BilibiliSubtitleMeta(
    val code: Int?,
    val message: String?,
    @SerialName("allow_submit")
    val allowSubmit: Boolean?,
    val subtitles: List<BilibiliSubtitleMetaSubtitleItem>?
)
