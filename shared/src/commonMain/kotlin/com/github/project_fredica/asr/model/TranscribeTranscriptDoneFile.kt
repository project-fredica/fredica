package com.github.project_fredica.asr.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 全量转录完成后写入的 transcript.done 内容（含输出哈希 + chunk 数量）。 */
@Serializable
data class TranscribeTranscriptDoneFile(
    @SerialName("output_hash") val outputHash: String,
    @SerialName("chunk_count") val chunkCount: Int,
)
