package com.github.project_fredica.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class LlmCallPayload(
    @SerialName("model_id") val modelId: String,
    @SerialName("base_url") val baseUrl: String,
    @SerialName("api_key") val apiKey: String,
    val model: String,
    @SerialName("system_prompt") val systemPrompt: String? = null,
    @SerialName("user_prompt") val userPrompt: String,
    @SerialName("response_format") val responseFormat: JsonElement? = null,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
)
