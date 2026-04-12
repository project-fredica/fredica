package com.github.project_fredica.asr.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ASR 权限与测试配置的完整响应（仅 jsBridge 返回给服主）。
 * 字段语义与 AppConfig 中的 asr_* 字段一一对应。
 */
@Serializable
data class AsrConfigResponse(
    @SerialName("allow_download") val allowDownload: Boolean,
    @SerialName("disallowed_models") val disallowedModels: String,
    @SerialName("test_audio_path") val testAudioPath: String,
    @SerialName("test_wave_count") val testWaveCount: Int,
)
