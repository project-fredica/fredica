package com.github.project_fredica.llm

/** LLM 提供商返回的业务错误（非 2xx HTTP 响应），含错误类型分类 */
class LlmProviderException(
    val type: Type,
    val httpStatus: Int,
    val providerMessage: String,
) : Exception("LLM provider error [$type] status=$httpStatus: $providerMessage") {
    enum class Type {
        AUTH_ERROR, RATE_LIMIT, SERVER_ERROR, CONTENT_FILTER, MODEL_NOT_FOUND, UNKNOWN
    }
}
