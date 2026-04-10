package com.github.project_fredica.asr.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 字幕内容请求参数 */
@Serializable
data class MaterialSubtitleContentParam(
    @SerialName("subtitle_url") val subtitleUrl: String,
    val source: String = "bilibili_platform",
    @SerialName("is_update") val isUpdate: Boolean = false,
)
