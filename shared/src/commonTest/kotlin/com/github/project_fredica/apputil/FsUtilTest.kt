@file:Suppress("NonAsciiCharacters", "RemoveRedundantBackticks")

package com.github.project_fredica.apputil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FilenameSlugifyTest {

    // ─── 基础行为 ─────────────────────────────────────────────────────────────

    @Test
    fun `普通 ASCII 字符串保持不变`() {
        assertEquals("hello-world", filenameSlugify("hello-world"))
    }

    @Test
    fun `空格转换为连字符`() {
        assertEquals("hello-world", filenameSlugify("hello world"))
    }

    @Test
    fun `转小写`() {
        assertEquals("hello-world", filenameSlugify("Hello World"))
    }

    @Test
    fun `连续空格合并为单个连字符`() {
        assertEquals("hello-world", filenameSlugify("hello   world"))
    }

    @Test
    fun `连续连字符合并为单个连字符`() {
        assertEquals("hello-world", filenameSlugify("hello---world"))
    }

    @Test
    fun `空白与连字符混合合并`() {
        assertEquals("hello-world", filenameSlugify("hello - world"))
    }

    // ─── 特殊字符过滤 ─────────────────────────────────────────────────────────

    @Test
    fun `去掉路径穿越字符`() {
        assertEquals("etc-passwd", filenameSlugify("../etc/passwd"))
    }

    @Test
    fun `去掉点号`() {
        assertEquals("filename", filenameSlugify("file.name"))
    }

    @Test
    fun `去掉斜杠`() {
        assertEquals("a-b", filenameSlugify("a/b"))
    }

    @Test
    fun `去掉反斜杠`() {
        assertEquals("a-b", filenameSlugify("a\\b"))
    }

    @Test
    fun `去掉特殊符号`() {
        assertEquals("helloworld", filenameSlugify("hello!@#\$%^&*()world"))
    }

    @Test
    fun `保留下划线`() {
        assertEquals("hello_world", filenameSlugify("hello_world"))
    }

    // ─── 首尾 strip ───────────────────────────────────────────────────────────

    @Test
    fun `去掉首尾连字符`() {
        assertEquals("hello", filenameSlugify("-hello-"))
    }

    @Test
    fun `去掉首尾下划线`() {
        assertEquals("hello", filenameSlugify("_hello_"))
    }

    @Test
    fun `去掉首尾连字符和下划线混合`() {
        assertEquals("hello", filenameSlugify("-_hello_-"))
    }

    // ─── Unicode / 中文 ───────────────────────────────────────────────────────

    @Test
    fun `中文字符保留`() {
        assertEquals("你好世界", filenameSlugify("你好世界"))
    }

    @Test
    fun `中文与英文混合`() {
        assertEquals("hello-你好", filenameSlugify("hello 你好"))
    }

    @Test
    fun `全角字母经 NFKC 转为半角后处理`() {
        assertEquals("a", filenameSlugify("Ａ"))
    }

    @Test
    fun `全角空格转为连字符`() {
        assertEquals("a-b", filenameSlugify("a\u3000b"))
    }

    // ─── 边界情况 ─────────────────────────────────────────────────────────────

    @Test
    fun `空字符串返回空字符串`() {
        assertEquals("", filenameSlugify(""))
    }

    @Test
    fun `全为特殊字符返回空字符串`() {
        assertEquals("", filenameSlugify("!@#\$%"))
    }

    @Test
    fun `SHA-256 哈希值保持不变`() {
        val hash = "a3f1c2d4e5b6789012345678901234567890abcdef1234567890abcdef123456"
        assertEquals(hash, filenameSlugify(hash))
    }

    @Test
    fun `UUID 连字符保持不变`() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        assertEquals(uuid, filenameSlugify(uuid))
    }

    // ─── 路径穿越攻击变体 ─────────────────────────────────────────────────────

    @Test
    fun `多级路径穿越`() {
        val result = filenameSlugify("../../etc/shadow")
        assertNotContains(result, "..")
        assertNotContains(result, "/")
        assertNotContains(result, "\\")
    }

    @Test
    fun `Windows 路径穿越`() {
        val result = filenameSlugify("..\\..\\Windows\\System32")
        assertNotContains(result, "..")
        assertNotContains(result, "\\")
    }

    @Test
    fun `绝对路径不能穿越`() {
        val result = filenameSlugify("/etc/passwd")
        assertNotContains(result, "/")
        assertEquals("etc-passwd", result)
    }

    @Test
    fun `Windows 绝对路径`() {
        val result = filenameSlugify("C:\\Windows\\System32")
        assertNotContains(result, "\\")
        assertNotContains(result, ":")
    }

    @Test
    fun `URL 编码的斜杠不能绕过`() {
        // %2F 是 URL 编码的 /，但 slugify 不做 URL 解码，% 和 2 F 各自处理
        val result = filenameSlugify("a%2Fb")
        assertNotContains(result, "/")
        // % 被删除，剩余 a2fb
        assertEquals("a2fb", result)
    }

    @Test
    fun `null 字节不能注入`() {
        val result = filenameSlugify("hello\u0000world")
        assertNotContains(result, "\u0000")
        assertEquals("helloworld", result)
    }

    @Test
    fun `换行符不能注入`() {
        val result = filenameSlugify("hello\nworld")
        assertNotContains(result, "\n")
        assertEquals("hello-world", result)
    }

    @Test
    fun `制表符不能注入`() {
        val result = filenameSlugify("hello\tworld")
        assertNotContains(result, "\t")
        assertEquals("hello-world", result)
    }

    @Test
    fun `混合路径穿越与正常内容`() {
        val result = filenameSlugify("valid/../../../etc/passwd")
        assertNotContains(result, "..")
        assertNotContains(result, "/")
    }

    // ─── Unicode 特殊攻击 ─────────────────────────────────────────────────────

    @Test
    fun `零宽字符被删除`() {
        val result = filenameSlugify("hel\u200Blo\u200Cworld")
        assertNotContains(result, "\u200B")
        assertNotContains(result, "\u200C")
        assertEquals("helloworld", result)
    }

    @Test
    fun `方向控制字符被删除`() {
        val result = filenameSlugify("hello\u202Eworld")
        assertNotContains(result, "\u202E")
    }

    @Test
    fun `组合字符规范化后不产生路径分隔符`() {
        val result = filenameSlugify("a\u0338b")
        assertNotContains(result, "/")
        assertNotContains(result, "\\")
    }

    @Test
    fun `全角斜杠经 NFKC 规范化为普通斜杠后被处理`() {
        val result = filenameSlugify("a\uFF0Fb")
        assertNotContains(result, "/")
        assertNotContains(result, "\uFF0F")
        assertEquals("a-b", result)
    }

    @Test
    fun `全角反斜杠经 NFKC 规范化后被处理`() {
        val result = filenameSlugify("a\uFF3Cb")
        assertNotContains(result, "\\")
        assertNotContains(result, "\uFF3C")
        assertEquals("a-b", result)
    }

    @Test
    fun `超长字符串不崩溃`() {
        val long = "a".repeat(10_000)
        val result = filenameSlugify(long)
        assertTrue(result.isNotEmpty())
        assertNotContains(result, "/")
    }

    @Test
    fun `仅空白字符返回空字符串`() {
        assertEquals("", filenameSlugify("   \t\n  "))
    }

    @Test
    fun `仅连字符和下划线返回空字符串`() {
        assertEquals("", filenameSlugify("---___---"))
    }

    @Test
    fun `点点不产生路径穿越`() {
        val result = filenameSlugify("..")
        assertNotContains(result, "..")
        assertEquals("", result)
    }

    @Test
    fun `单点返回空字符串`() {
        assertEquals("", filenameSlugify("."))
    }

    @Test
    fun `冒号被删除（Windows 驱动器号攻击）`() {
        val result = filenameSlugify("C:")
        assertNotContains(result, ":")
        assertEquals("c", result)
    }

    @Test
    fun `结果不以连字符开头或结尾`() {
        val inputs = listOf("/leading", "trailing/", "/both/", "  spaces  ", "-dash-")
        for (input in inputs) {
            val result = filenameSlugify(input)
            assertTrue(
                result.isEmpty() || (!result.startsWith("-") && !result.endsWith("-")),
                "输入 '$input' 的结果 '$result' 不应以连字符开头或结尾"
            )
        }
    }

    // ─── 辅助函数 ─────────────────────────────────────────────────────────────

    private fun assertNotContains(value: String, substring: String) {
        assertTrue(
            !value.contains(substring),
            "期望结果不包含 '$substring'，但实际结果为 '$value'"
        )
    }
}
