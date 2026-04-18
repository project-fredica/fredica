package com.github.project_fredica.material_category.model

import kotlinx.serialization.Serializable

@Serializable
data class MaterialCategoryListRequest(
    val filter: MaterialCategoryFilter = MaterialCategoryFilter.ALL,
    val search: String? = null,
    val offset: Int = 0,
    val limit: Int = 50,
)
