package com.github.project_fredica.material_category.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MaterialCategoryAuditLog(
    val id: String,
    @SerialName("category_id") val categoryId: String,
    @SerialName("user_id") val userId: String,
    val action: String,
    val detail: String = "{}",
    @SerialName("created_at") val createdAt: Long,
)
