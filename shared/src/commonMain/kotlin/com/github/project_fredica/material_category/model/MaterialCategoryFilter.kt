package com.github.project_fredica.material_category.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MaterialCategoryFilter {
    @SerialName("all") ALL,
    @SerialName("mine") MINE,
    @SerialName("sync") SYNC,
    @SerialName("public") PUBLIC,
}
