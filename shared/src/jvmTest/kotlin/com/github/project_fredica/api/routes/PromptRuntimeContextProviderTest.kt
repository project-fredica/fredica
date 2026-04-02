package com.github.project_fredica.api.routes

// =============================================================================
// PromptRuntimeContextProviderTest — 纯函数方法单元测试
// =============================================================================
//
// 仅测试不依赖 DB 的方法：
//   P1  getSchemaHint("weben_schema_hint") → 非空，含 concepts
//   P2  getSchemaHint("weben/summary") 与 "weben_schema_hint" 返回相同内容
//   P3  getSchemaHint 包含 JSON Schema 结构关键词
//   P4  getSchemaHint 包含 concept type example/examples，且类型为开放文本
//   P5  getSchemaHint("__unknown__") → 空串
//   P6  getVar("__unknown__") → 空串
//   P7  getVar 配额超过 MAX_API_CALLS 后抛异常
//   P8  getSchemaHint 不计入 getVar 配额
//
// DB 依赖的 material/{id}/... 路径需真实服务初始化，
// 留待集成测试覆盖。
// =============================================================================

import com.github.project_fredica.prompt.PromptRuntimeContextProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptRuntimeContextProviderTest {

    private val provider = PromptRuntimeContextProvider()

    @Test
    fun `P1 getSchemaHint weben_schema_hint contains structure keywords`() {
        val hint = provider.getSchemaHint("weben_schema_hint")

        assertTrue(hint.isNotBlank(), "hint 不应为空")
        assertTrue(hint.contains("concepts"), "应含 concepts")
        assertFalse(hint.contains("relations"), "不应再含 relations")
        assertFalse(hint.contains("flashcards"), "不应再含 flashcards")
    }

    @Test
    fun `P2 getSchemaHint weben slash summary equals weben_schema_hint`() {
        val hint1 = provider.getSchemaHint("weben_schema_hint")
        val hint2 = provider.getSchemaHint("weben/summary")

        assertEquals(hint1, hint2, "两个 key 应返回相同 hint")
    }

    @Test
    fun `P3 getSchemaHint includes json schema keywords`() {
        val hint = provider.getSchemaHint("weben_schema_hint")

        assertTrue(hint.contains("\"type\""), "应含 JSON Schema type")
        assertTrue(hint.contains("\"properties\""), "应含 JSON Schema properties")
        assertTrue(hint.contains("\"concepts\""), "应含 concepts 字段定义")
    }

    @Test
    fun `P4 getSchemaHint includes open concept type examples metadata`() {
        val hint = provider.getSchemaHint("weben_schema_hint")

        assertTrue(hint.contains("自由文本"), "应说明 type 为自由文本")
        assertTrue(hint.contains("\"example\""), "应含 JSON Schema example")
        assertTrue(hint.contains("\"examples\""), "应含 JSON Schema examples")
        assertTrue(hint.contains("数据结构"), "应含默认示例 '数据结构'")
    }

    @Test
    fun `P5 getSchemaHint unknown key returns empty string`() {
        val hint = provider.getSchemaHint("__no_such_key__")

        assertEquals("", hint)
    }

    @Test
    fun `P6 getVar unknown key returns empty string`() {
        val value = provider.getVar("__no_such_key__")

        assertEquals("", value)
    }

    @Test
    fun `P7 getVar throws after exceeding max api calls`() {
        repeat(PromptRuntimeContextProvider.MAX_API_CALLS) {
            provider.getVar("__no_such_key__")
        }

        val error = assertFailsWith<RuntimeException> {
            provider.getVar("__no_such_key__")
        }
        assertTrue(error.message?.contains("API 调用次数超出限制") == true)
    }

    @Test
    fun `P8 getSchemaHint does not consume getVar quota`() {
        repeat(100) {
            provider.getSchemaHint("weben_schema_hint")
        }

        repeat(PromptRuntimeContextProvider.MAX_API_CALLS) {
            provider.getVar("__no_such_key__")
        }
    }
}
