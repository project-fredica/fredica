package com.github.project_fredica.orm.app_entities

import com.github.project_fredica.orm.Col
import kotlinx.serialization.Serializable

@Serializable
data class ModelConfig(
    @Col(isId = true)
    val id: Long,
    @Col
    val baseUrl: String?,
    @Col
    val apiToken: String?,
    val modelName: String?,
)