package com.github.project_fredica.asr.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ASR 配置的公开子集（通过 HTTP route 返回给普通用户）。
 * 仅包含普通用户需要的权限信息，不暴露服主的测试参数。
 */
@Serializable
data class AsrConfigPublicResponse(
    @SerialName("allow_download") val allowDownload: Boolean,
    @SerialName("disallowed_models") val disallowedModels: String,
)
