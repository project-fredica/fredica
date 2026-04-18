package com.github.project_fredica.material_category.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MaterialCategorySyncUserConfig(
    val id: String,
    @SerialName("platform_info_id") val platformInfoId: String,
    @SerialName("user_id") val userId: String,
    val enabled: Boolean = true,
    @SerialName("cron_expr") val cronExpr: String = "0 */6 * * *",
    @SerialName("freshness_window_sec") val freshnessWindowSec: Int = 3600,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
)
