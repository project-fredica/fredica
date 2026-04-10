package com.github.project_fredica.asr.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** chunk 转录完成时写入的 chunk_XXXX.done 内容（含输入/输出哈希用于幂等校验）。 */
@Serializable
data class TranscribeChunkDoneFile(
    @SerialName("input_hash") val inputHash: String,
    @SerialName("output_hash") val outputHash: String,
    @SerialName("model_size") val modelSize: String,
    val language: String?,
)
