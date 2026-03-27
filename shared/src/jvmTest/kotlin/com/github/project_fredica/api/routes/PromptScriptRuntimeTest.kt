package com.github.project_fredica.api.routes

// =============================================================================
// PromptScriptRuntimeTest — GraalJS 沙箱执行引擎单元测试
// =============================================================================
//
// 覆盖场景：
//   S1  同步 main() 返回字符串 → promptText 正确
//   S2  异步 main()（async/await）→ promptText 正确
//   S3  main() 抛出异常 → error 非空，errorType = "script_error"
//   S4  未定义 main() → error 含 "main"
//   S5  console.log 被收集到 logs，level = "log"
//   S6  console.warn → level = "warn"
//   S7  console.error → level = "error"
//   S8  多条日志按调用顺序收集
//   S9  getSchemaHint("weben_schema_hint") 从脚本中可调用，返回非空
//   S10 getVar("__unknown__") → 脚本收到空串（else 分支，无 DB 依赖）
//   S11 main() 返回 null → promptText = ""（包装层 null 转空串）
//   S12 main() 返回数字 → promptText = String(number)
//   S13 异步 main() 内部 rejection → error 非空，errorType = "script_error"
//
// 注意：
//   - 所有脚本均不调用依赖 DB 的 getVar("material.title") / readRoute()，
//     因此不需要初始化任何数据库服务。
//   - 超时测试（需等待 5s+）不包含在单元测试中，应在集成测试中单独验证。
// =============================================================================

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptScriptRuntimeTest {

    /** 便捷执行入口（PREVIEW 模式，超时 5s）。 */
    private fun run(script: String): PromptSandboxResult = runBlocking {
        PromptScriptRuntime.execute(script, PromptScriptRuntime.Mode.PREVIEW)
    }

    // ── S1: 同步 main() 返回字符串 ────────────────────────────────────────────

    @Test
    fun `S1 sync main returns string as promptText`() {
        val result = run("""function main() { return 'hello world'; }""")

        assertNull(result.error, "预期无错误，但得到: ${result.error}")
        assertEquals("hello world", result.promptText)
    }

    // ── S2: 异步 main() 经 await 返回字符串 ──────────────────────────────────

    @Test
    fun `S2 async main with await resolves to promptText`() {
        val result = run("""
            async function main() {
                return 'async result';
            }
        """.trimIndent())

        assertNull(result.error, "预期无错误，但得到: ${result.error}")
        assertEquals("async result", result.promptText)
    }

    // ── S3: main() 同步抛出异常 ───────────────────────────────────────────────

    @Test
    fun `S3 main throws produces script_error`() {
        val result = run("""function main() { throw new Error('boom'); }""")

        assertNotNull(result.error)
        assertTrue(result.error.contains("boom"), "error 应包含异常消息，实际: ${result.error}")
        assertEquals("script_error", result.errorType)
        assertNull(result.promptText)
    }

    // ── S4: 未定义 main() ─────────────────────────────────────────────────────

    @Test
    fun `S4 missing main function produces error mentioning main`() {
        val result = run("""var x = 1;""")

        assertNotNull(result.error)
        assertTrue(result.error.contains("main"), "error 应提示 main() 缺失，实际: ${result.error}")
        assertNull(result.promptText)
    }

    // ── S5: console.log 收集到 logs，level = "log" ───────────────────────────

    @Test
    fun `S5 console log captured with level log`() {
        val result = run("""
            function main() {
                console.log('hello from log');
                return 'ok';
            }
        """.trimIndent())

        assertEquals("ok", result.promptText)
        assertTrue(result.logs.isNotEmpty(), "logs 应非空")
        val entry = result.logs.first()
        assertEquals("log", entry.level)
        assertTrue(entry.args.contains("hello from log"))
    }

    // ── S6: console.warn → level = "warn" ────────────────────────────────────

    @Test
    fun `S6 console warn captured with level warn`() {
        val result = run("""
            function main() {
                console.warn('attention');
                return '';
            }
        """.trimIndent())

        assertTrue(result.logs.any { it.level == "warn" && it.args.contains("attention") },
            "logs 中应有 level=warn 的条目")
    }

    // ── S7: console.error → level = "error" ──────────────────────────────────

    @Test
    fun `S7 console error captured with level error`() {
        val result = run("""
            function main() {
                console.error('critical');
                return '';
            }
        """.trimIndent())

        assertTrue(result.logs.any { it.level == "error" && it.args.contains("critical") },
            "logs 中应有 level=error 的条目")
    }

    // ── S8: 多条日志按调用顺序收集 ────────────────────────────────────────────

    @Test
    fun `S8 multiple console logs collected in call order`() {
        val result = run("""
            function main() {
                console.log('first');
                console.log('second');
                console.log('third');
                return 'done';
            }
        """.trimIndent())

        assertEquals(3, result.logs.size, "应有 3 条日志")
        assertEquals("first",  result.logs[0].args)
        assertEquals("second", result.logs[1].args)
        assertEquals("third",  result.logs[2].args)
    }

    // ── S9: getSchemaHint 在脚本中可调用，返回非空 Weben 结构约束文本 ─────────

    @Test
    fun `S9 getSchemaHint weben_schema_hint injectable from script`() {
        val result = run("""
            async function main() {
                var hint = await getSchemaHint('weben_schema_hint');
                return hint;
            }
        """.trimIndent())

        assertNull(result.error, "预期无错误，但得到: ${result.error}")
        assertNotNull(result.promptText)
        // 生成后的 JSON Schema 仍需包含核心结构字段
        assertTrue(result.promptText.contains("concepts"), "hint 应含 'concepts'")
        assertTrue(result.promptText.contains("relations"), "hint 应含 'relations'")
        assertTrue(result.promptText.contains("flashcards"), "hint 应含 'flashcards'")
        assertTrue(result.promptText.contains("\"properties\""), "hint 应含 JSON Schema properties")
    }

    // ── S10: getVar 未知 key → 空串（无 DB 依赖）────────────────────────────

    @Test
    fun `S10 getVar unknown key returns empty string`() {
        val result = run("""
            async function main() {
                var v = await getVar('__no_such_key__');
                return v === '' ? 'empty' : 'non-empty';
            }
        """.trimIndent())

        assertNull(result.error, "预期无错误，但得到: ${result.error}")
        assertEquals("empty", result.promptText)
    }

    // ── S11: main() 返回 null → promptText = "" ──────────────────────────────

    @Test
    fun `S11 main returning null yields empty string promptText`() {
        // 包装层：__result = (__ret == null ? '' : String(__ret))
        val result = run("""function main() { return null; }""")

        assertNull(result.error)
        assertEquals("", result.promptText)
    }

    // ── S12: main() 返回数字 → String(number) ────────────────────────────────

    @Test
    fun `S12 main returning number is coerced to string`() {
        val result = run("""function main() { return 42; }""")

        assertNull(result.error)
        assertEquals("42", result.promptText)
    }

    // ── S13: 异步 main() 内部 rejection ──────────────────────────────────────

    @Test
    fun `S13 async main rejection produces script_error`() {
        val result = run("""
            async function main() {
                throw new Error('async boom');
            }
        """.trimIndent())

        assertNotNull(result.error)
        assertTrue(result.error.contains("async boom"), "error 应含异常消息，实际: ${result.error}")
        assertEquals("script_error", result.errorType)
        assertNull(result.promptText)
    }
}
