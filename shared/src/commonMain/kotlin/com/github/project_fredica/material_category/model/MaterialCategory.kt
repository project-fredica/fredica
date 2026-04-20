package com.github.project_fredica.material_category.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MaterialCategory(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    val name: String,
    val description: String,
    @SerialName("allow_others_view") val allowOthersView: Boolean = false,
    @SerialName("allow_others_add") val allowOthersAdd: Boolean = false,
    @SerialName("allow_others_delete") val allowOthersDelete: Boolean = false,
    @SerialName("material_count") val materialCount: Int = 0,
    @SerialName("is_mine") val isMine: Boolean = true,
    val sync: MaterialCategorySyncPlatformInfoSummary? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
)

object MaterialCategoryDefaults {
    fun uncategorizedId(userId: String) = "uncategorized:$userId"
    const val UNCATEGORIZED_NAME = "待分类"

    fun isUncategorized(categoryId: String) = categoryId.startsWith("uncategorized:")
}
