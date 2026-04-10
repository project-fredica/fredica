package com.github.project_fredica.asr.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 字幕内容缓存（单条字幕 body，按 url_key 索引） */
@Serializable
data class BilibiliSubtitleBodyCache(
    val id: Long = 0,
    @SerialName("url_key") val urlKey: String,
    @SerialName("queried_at") val queriedAt: Long,
    @SerialName("raw_result") val rawResult: String,
    @SerialName("is_success") val isSuccess: Boolean,
)
