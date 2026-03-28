package com.github.project_fredica.llm

import com.github.project_fredica.apputil.sha256Hex
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
object LlmCacheKeyUtil {

    /**
     * 构建可读缓存键：
     *   base64(model_name) + "|" + base64(baseUrl) + "|" + base64(messagesJsonCanonical)
     * 每段独立编码，| 为固定分隔符（不出现在 Base64 字母表中）。
     */
    fun buildCacheKey(
        modelName: String,
        baseUrl: String,
        messagesJson: LlmMessagesJson,
    ): String {
        fun enc(s: String) = Base64.encode(s.encodeToByteArray())
        return "${enc(modelName)}|${enc(baseUrl.trimEnd('/'))}|${enc(messagesJson.canonicalize())}"
    }

    /**
     * 从 cache_key 反序列化出原始三段内容（调试 / 审计用）。
     * 返回 Triple(modelName, baseUrl, messagesJsonCanonical)，失败返回 null。
     */
    fun parseCacheKey(cacheKey: String): Triple<String, String, String>? = runCatching {
        fun dec(s: String) = Base64.decode(s).decodeToString()
        val parts = cacheKey.split("|")
        if (parts.size != 3) return null
        Triple(dec(parts[0]), dec(parts[1]), dec(parts[2]))
    }.getOrNull()

    /**
     * 计算 cache_key 的 SHA-256 哈希（DB UNIQUE 约束键）。
     */
    fun hashKey(cacheKey: String): String = sha256Hex(cacheKey)
}
