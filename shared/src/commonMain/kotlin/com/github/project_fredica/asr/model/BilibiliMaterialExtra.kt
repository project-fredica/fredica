package com.github.project_fredica.asr.model

import kotlinx.serialization.Serializable

/** 从 Material.extra JSON 中提取 bvid 字段，忽略其他字段。 */
@Serializable
data class BilibiliMaterialExtra(
    val bvid: String? = null,
)
