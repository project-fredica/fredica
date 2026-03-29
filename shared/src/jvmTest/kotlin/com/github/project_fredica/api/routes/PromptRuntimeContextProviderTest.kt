package com.github.project_fredica.api.routes

// =============================================================================
// PromptRuntimeContextProviderTest — 纯函数方法单元测试
// =============================================================================
//
// 仅测试不依赖 DB 的方法：
//   P1  getSchemaHint("weben_schema_hint") → 非空，含 concepts/relations/flashcards
//   P2  getSchemaHint("weben/summary") 与 "weben_schema_hint" 返回相同内容
//   P3  getSchemaHint 包含预定义概念类型枚举（术语、理论…）
//   P4  getSchemaHint 包含预定义谓词枚举（包含、依赖…）
//   P5  getSchemaHint("material/{id}/title") → 非空描述，非 "unknown"
//   P6  getSchemaHint("__unknown__") → "unknown"
//
// DB 依赖的 getVar("material/{id}/...") / readRoute() 需真实服务初始化，
// 留待集成测试覆盖。
// =============================================================================

import com.github.project_fredica.prompt.PromptRuntimeContextProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PromptRuntimeContextProviderTest {

    // materialId 由 lambda 提供，测试只调用纯函数（不触发 DB 访问）
    private val provider = PromptRuntimeContextProvider()

    // ── P1: getSchemaHint("weben_schema_hint") 包含结构关键词 ─────────────────

    @Test
    fun `P1 getSchemaHint weben_schema_hint contains structure keywords`() {
        val hint = provider.getSchemaHint("weben_schema_hint")

        assertTrue(hint.isNotBlank(), "hint 不应为空")
        assertTrue(hint.contains("concepts"),  "应含 concepts")
        assertTrue(hint.contains("relations"), "应含 relations")
        assertTrue(hint.contains("flashcards"),"应含 flashcards")
    }

    // ── P2: "weben/summary" 与 "weben_schema_hint" 返回相同内容 ─────────────

    @Test
    fun `P2 getSchemaHint weben slash summary equals weben_schema_hint`() {
        val hint1 = provider.getSchemaHint("weben_schema_hint")
        val hint2 = provider.getSchemaHint("weben/summary")

        assertEquals(hint1, hint2, "两个 key 应返回相同 hint")
    }

    // ── P3: hint 含 JSON Schema 结构关键词 ───────────────────────────────────

    @Test
    fun `P3 getSchemaHint includes json schema keywords`() {
        val hint = provider.getSchemaHint("weben_schema_hint")

        assertTrue(hint.contains("\"type\""), "应含 JSON Schema type")
        assertTrue(hint.contains("\"properties\""), "应含 JSON Schema properties")
        assertTrue(hint.contains("\"\$defs\""), "应含 JSON Schema defs")
    }

    // ── P4: hint 字段 description 中包含概念类型与谓词示例 ─────────────────────

    @Test
    fun `P4 getSchemaHint includes concept type and predicate examples in descriptions`() {
        val hint = provider.getSchemaHint("weben_schema_hint")

        assertTrue(hint.contains("术语"), "应含概念类型示例 '术语'")
        assertTrue(hint.contains("理论"), "应含概念类型示例 '理论'")
        assertTrue(hint.contains("包含"), "应含谓词示例 '包含'")
        assertTrue(hint.contains("依赖"), "应含谓词示例 '依赖'")
    }

    // ── P5: getSchemaHint("material/{id}/title") 返回字段描述 ─────────────────

    @Test
    fun `P5 getSchemaHint material path title returns field description`() {
        val hint = provider.getSchemaHint("material/test-id/title")

        assertNotNull(hint)
        assertTrue(hint.isNotBlank(), "描述不应为空")
        assertFalse(hint == "unknown", "已知路径不应返回 'unknown'")
    }

    // ── P6: 未知 key → "unknown" ─────────────────────────────────────────────

    @Test
    fun `P6 getSchemaHint unknown key returns unknown`() {
        val hint = provider.getSchemaHint("__no_such_key__")

        assertEquals("unknown", hint)
    }
}
