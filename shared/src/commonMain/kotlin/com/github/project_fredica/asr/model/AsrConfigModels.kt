package com.github.project_fredica.asr.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ASR 权限与测试配置的读取响应。
 * 字段语义与 AppConfig 中的 asr_* 字段一一对应。
 */
@Serializable
data class AsrConfigResponse(
    @SerialName("allow_download") val allowDownload: Boolean,
    @SerialName("disallowed_models") val disallowedModels: String,
    @SerialName("test_audio_path") val testAudioPath: String,
    @SerialName("test_wave_count") val testWaveCount: Int,
)

/**
 * ASR 配置的部分更新参数。所有字段可选，仅非 null 字段会被写入。
 */
@Serializable
data class AsrConfigSaveParam(
    @SerialName("allow_download") val allowDownload: Boolean? = null,
    @SerialName("disallowed_models") val disallowedModels: String? = null,
    @SerialName("test_audio_path") val testAudioPath: String? = null,
    @SerialName("test_wave_count") val testWaveCount: Int? = null,
)
