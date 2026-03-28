package com.github.project_fredica.llm

import com.github.project_fredica.apputil.jsonCanonical

/** 包装 messages 列表的原始 JSON 字符串，避免强类型绑定造成的字段丢失 */
@JvmInline
value class LlmMessagesJson(val raw: String) {
    /** 规范化：调用通用 jsonCanonical，消除 key 顺序/空格差异 */
    fun canonicalize(): String = jsonCanonical(raw)
}
