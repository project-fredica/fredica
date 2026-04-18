package com.github.project_fredica.material_category.model

import kotlinx.serialization.Serializable

@Serializable
data class MaterialCategoryListResponse(
    val items: List<MaterialCategory>,
    val total: Int,
    val offset: Int,
    val limit: Int,
)
