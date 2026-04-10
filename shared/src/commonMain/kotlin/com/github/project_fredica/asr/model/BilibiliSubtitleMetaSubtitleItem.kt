package com.github.project_fredica.asr.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Bilibili 字幕轨条目（元信息中的单条字幕轨描述）。 */
@Serializable
data class BilibiliSubtitleMetaSubtitleItem(
    val id: Long?,
    val lan: String?,
    @SerialName("lan_doc")
    val lanDoc: String?,
    @SerialName("is_lock")
    val isLock: Boolean?,
    @SerialName("subtitle_url")
    val subtitleUrl: String?,
    @SerialName("subtitle_url_v2")
    val subtitleUrlV2: String?,
    val type: Int?,
    @SerialName("id_str")
    val idStr: String?,
    @SerialName("ai_type")
    val aiType: Int?,
    @SerialName("ai_status")
    val aiStatus: Int?,
)
