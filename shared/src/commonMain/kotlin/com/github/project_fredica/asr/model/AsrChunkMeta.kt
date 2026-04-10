package com.github.project_fredica.asr.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 反序列化 chunk_XXXX.meta.json */
@Serializable
data class AsrChunkMeta(
    @SerialName("model_size") val modelSize: String? = null,
    val language: String? = null,
    @SerialName("segment_count") val segmentCount: Int? = null,
)
