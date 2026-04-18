package com.github.project_fredica.material_category.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MaterialCategorySyncItem(
    val id: String,
    @SerialName("platform_info_id") val platformInfoId: String,
    @SerialName("material_id") val materialId: String,
    @SerialName("platform_item_id") val platformItemId: String = "",
    @SerialName("synced_at") val syncedAt: Long,
)
