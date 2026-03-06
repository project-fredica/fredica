package com.github.project_fredica.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class LlmProviderType { OPENAI_COMPATIBLE }

enum class LlmCapability {
    VISION, JSON_SCHEMA, STREAMING, MCP, FUNCTION_CALLING, LONG_CONTEXT
}

@Serializable
data class LlmModelConfig(
    val id: String,
    val name: String,
    @SerialName("provider_type") val providerType: LlmProviderType = LlmProviderType.OPENAI_COMPATIBLE,
    @SerialName("base_url") val baseUrl: String,
    @SerialName("api_key") val apiKey: String,
    val model: String,
    val capabilities: Set<LlmCapability> = setOf(LlmCapability.STREAMING),
    @SerialName("context_window") val contextWindow: Int = 8192,
    @SerialName("max_output_tokens") val maxOutputTokens: Int = 4096,
    val temperature: Double = 0.7,
    val notes: String = "",
    @SerialName("sort_order") val sortOrder: Int = 0,
    /**
     * 应用内模型标识符，供前端通过安全接口调用时使用。
     * 前端只需传 appModelId，后端据此查找真实的 api_key / base_url，避免敏感信息暴露。
     * 若保存时为空，后端自动生成（模型名 slug + 随机 6 位字母数字后缀），全局唯一。
     */
    @SerialName("app_model_id") val appModelId: String = "",
)

@Serializable
data class LlmDefaultRoles(
    @SerialName("chat_model_id") val chatModelId: String = "",
    @SerialName("vision_model_id") val visionModelId: String = "",
    @SerialName("coding_model_id") val codingModelId: String = "",
    @SerialName("dev_test_model_id") val devTestModelId: String = "",
)
