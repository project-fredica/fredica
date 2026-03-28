package com.github.project_fredica.llm

import kotlinx.serialization.json.JsonObject

/**
 * LLM 调用入参。
 * messages 使用 [LlmMessagesJson] 包装原始 JSON 字符串，不做强类型绑定，
 * 避免因 OpenAI-compatible API 的 message 格式多样（vision、function call 等）导致字段丢失。
 */
data class LlmRequest(
    val modelConfig: LlmModelConfig,
    /** 原始 messages JSON 字符串（由调用方构造，可含 system/user/assistant 等任意合法格式） */
    val messages: LlmMessagesJson,
    /** 其他 OpenAI 请求体字段（response_format、temperature、max_tokens 等），原样透传 */
    val extraFields: JsonObject? = null,
    /**
     * true = 跳过缓存读取，强制请求 LLM。
     * 是否覆盖旧缓存取决于 [overwriteOnDisable]。
     */
    val disableCache: Boolean = false,
    /**
     * disableCache=true 时，是否覆盖写入缓存。
     * - true（默认）：以新结果覆盖旧缓存（is_valid 恢复为 1）
     * - false：不写缓存（适用于"保留修订内容"等场景，后期修订表接入后可按业务设置）
     */
    val overwriteOnDisable: Boolean = true,
)

data class LlmResponse(
    val text: String,
    val source: LlmResponseSource,
    val keyHash: String,
    val cacheKey: String,
)

enum class LlmResponseSource { CACHE, LLM_FRESH, REVISION /* 后期 */ }
