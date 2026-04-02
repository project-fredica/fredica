package com.github.project_fredica.prompt

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
//   S9  getSchemaHint("weben_schema_hint") 从脚本中可调用，返回非空 concept-only schema
//   S10 getSchemaHint("weben/summary") 与兼容别名返回相同 schema
//   S11 getSchemaHint("__unknown__") → 脚本收到空串
//   S12 getVar("__unknown__") → 脚本收到空串（else 分支，无 DB 依赖）
//   S13 main() 返回 null → promptText = ""（包装层 null 转空串）
//   S14 main() 返回数字 → promptText = String(number)
//   S15 异步 main() 内部 rejection → error 非空，errorType = "script_error"
//
// 注意：
//   - 所有脚本均不调用依赖 DB 的 getVar("material.title")，因此不需要初始化任何数据库服务。
//   - 超时测试（需等待 5s+）不包含在单元测试中，应在集成测试中单独验证。
// =============================================================================

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptScriptRuntimeTest {

    private fun run(script: String): PromptSandboxResult = runBlocking {
        PromptScriptRuntime.execute(script, PromptScriptRuntime.Mode.PREVIEW)
    }

    @Test
    fun `S1 sync main returns string as promptText`() {
        val result = run("""function main() { return 'hello world'; }""")

        assertNull(result.error, "预期无错误，但得到: ${result.error}")
        assertEquals("hello world", result.promptText)
    }

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

    @Test
    fun `S3 main throws produces script_error`() {
        val result = run("""function main() { throw new Error('boom'); }""")

        assertNotNull(result.error)
        assertTrue(result.error.contains("boom"), "error 应包含异常消息，实际: ${result.error}")
        assertEquals("script_error", result.errorType)
        assertNull(result.promptText)
    }

    @Test
    fun `S4 missing main function produces error mentioning main`() {
        val result = run("""var x = 1;""")

        assertNotNull(result.error)
        assertTrue(result.error.contains("main"), "error 应提示 main() 缺失，实际: ${result.error}")
        assertNull(result.promptText)
    }

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
        assertEquals("first", result.logs[0].args)
        assertEquals("second", result.logs[1].args)
        assertEquals("third", result.logs[2].args)
    }

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
        assertTrue(result.promptText.contains("concepts"), "hint 应含 'concepts'")
        assertTrue(result.promptText.contains("\"example\""), "hint 应含 JSON Schema example")
        assertTrue(result.promptText.contains("\"examples\""), "hint 应含 JSON Schema examples")
        assertTrue(result.promptText.contains("数据结构"), "hint 应含开放类型示例 '数据结构'")
        assertTrue(result.promptText.contains("自由文本"), "hint 应说明 type 为自由文本")
    }

    @Test
    fun `S10 getSchemaHint weben slash summary equals compatibility alias`() {
        val result = run("""
            async function main() {
                var hint1 = await getSchemaHint('weben/summary');
                var hint2 = await getSchemaHint('weben_schema_hint');
                return hint1 === hint2 ? 'same' : 'different';
            }
        """.trimIndent())

        assertNull(result.error, "预期无错误，但得到: ${result.error}")
        assertEquals("same", result.promptText)
    }

    @Test
    fun `S11 getSchemaHint unknown key returns empty string`() {
        val result = run("""
            async function main() {
                var hint = await getSchemaHint('__no_such_key__');
                return hint === '' ? 'empty' : 'non-empty';
            }
        """.trimIndent())

        assertNull(result.error, "预期无错误，但得到: ${result.error}")
        assertEquals("empty", result.promptText)
    }

    @Test
    fun `S12 getVar unknown key returns empty string`() {
        val result = run("""
            async function main() {
                var v = await getVar('__no_such_key__');
                return v === '' ? 'empty' : 'non-empty';
            }
        """.trimIndent())

        assertNull(result.error, "预期无错误，但得到: ${result.error}")
        assertEquals("empty", result.promptText)
    }

    @Test
    fun `S13 main returning null yields empty string promptText`() {
        val result = run("""function main() { return null; }""")

        assertNull(result.error)
        assertEquals("", result.promptText)
    }

    @Test
    fun `S14 main returning number is coerced to string`() {
        val result = run("""function main() { return 42; }""")

        assertNull(result.error)
        assertEquals("42", result.promptText)
    }

    @Test
    fun `S15 async main rejection produces script_error`() {
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
