package com.github.project_fredica.worker.executors

// =============================================================================
// TranscribeExecutorSrtTest
// =============================================================================
//
// 被测对象：TranscribeExecutor 的 SRT 生成逻辑（buildSrt / formatSrtTime）
//
// 策略：通过反射调用私有方法，不依赖 Python / WebSocket / DB。
//
// 测试矩阵：
//   S1. formatSrtTime - 整秒（无毫秒）
//   S2. formatSrtTime - 含毫秒
//   S3. formatSrtTime - 超过 1 小时
//   S4. buildSrt - 空列表 → 空字符串
//   S5. buildSrt - 单段 → 正确序号/时间轴/文本
//   S6. buildSrt - 多段 → 序号递增，段间有空行
//   S7. buildSrt - 文本含特殊字符不被转义
// =============================================================================

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscribeExecutorSrtTest {

    // ── 反射辅助 ──────────────────────────────────────────────────────────────

    private fun formatSrtTime(sec: Double): String {
        val m = TranscribeExecutor::class.java
            .getDeclaredMethod("formatSrtTime", Double::class.javaPrimitiveType)
            .also { it.isAccessible = true }
        return m.invoke(TranscribeExecutor, sec) as String
    }

    /** Segment 是 TranscribeExecutor 的私有内部类，用反射构造实例 */
    private fun makeSegment(start: Double, end: Double, text: String): Any {
        val cls = TranscribeExecutor::class.java.declaredClasses
            .first { it.simpleName == "Segment" }
        val ctor = cls.getDeclaredConstructor(Double::class.javaPrimitiveType, Double::class.javaPrimitiveType, String::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(start, end, text)
    }

    private fun buildSrt(segments: List<Any>): String {
        val segCls = TranscribeExecutor::class.java.declaredClasses
            .first { it.simpleName == "Segment" }
        val listType = List::class.java
        val m = TranscribeExecutor::class.java
            .getDeclaredMethod("buildSrt", listType)
            .also { it.isAccessible = true }
        return m.invoke(TranscribeExecutor, segments) as String
    }

    // ── S1 ───────────────────────────────────────────────────────────────────

    @Test
    fun `S1 - formatSrtTime whole seconds`() {
        assertEquals("00:00:05,000", formatSrtTime(5.0))
        assertEquals("00:01:00,000", formatSrtTime(60.0))
    }

    // ── S2 ───────────────────────────────────────────────────────────────────

    @Test
    fun `S2 - formatSrtTime with milliseconds`() {
        assertEquals("00:00:01,500", formatSrtTime(1.5))
        assertEquals("00:00:00,123", formatSrtTime(0.123))
    }

    // ── S3 ───────────────────────────────────────────────────────────────────

    @Test
    fun `S3 - formatSrtTime over one hour`() {
        // 1h 2m 3.456s
        val sec = 3600.0 + 2 * 60.0 + 3.456
        assertEquals("01:02:03,456", formatSrtTime(sec))
    }

    // ── S4 ───────────────────────────────────────────────────────────────────

    @Test
    fun `S4 - buildSrt empty list returns empty string`() {
        assertEquals("", buildSrt(emptyList()).trim())
    }

    // ── S5 ───────────────────────────────────────────────────────────────────

    @Test
    fun `S5 - buildSrt single segment has correct structure`() {
        val seg = makeSegment(0.0, 1.5, "你好世界")
        val srt = buildSrt(listOf(seg))
        val lines = srt.lines().filter { it.isNotBlank() }

        assertEquals("1",                                    lines[0], "序号应为 1")
        assertEquals("00:00:00,000 --> 00:00:01,500",        lines[1], "时间轴格式错误")
        assertEquals("你好世界",                              lines[2], "文本内容错误")
    }

    // ── S6 ───────────────────────────────────────────────────────────────────

    @Test
    fun `S6 - buildSrt multiple segments have incrementing indices and blank separators`() {
        val segs = listOf(
            makeSegment(0.0,  1.0, "第一段"),
            makeSegment(1.0,  2.5, "第二段"),
            makeSegment(2.5,  4.0, "第三段"),
        )
        val srt = buildSrt(segs)

        assertTrue(srt.contains("1\n"), "应包含序号 1")
        assertTrue(srt.contains("2\n"), "应包含序号 2")
        assertTrue(srt.contains("3\n"), "应包含序号 3")
        assertTrue(srt.contains("第一段"), "应包含第一段文本")
        assertTrue(srt.contains("第二段"), "应包含第二段文本")
        assertTrue(srt.contains("第三段"), "应包含第三段文本")

        // 段间应有空行（连续两个换行）
        assertTrue(srt.contains("\n\n"), "段间应有空行")
    }

    // ── S7 ───────────────────────────────────────────────────────────────────

    @Test
    fun `S7 - buildSrt preserves special characters without escaping`() {
        val text = "Hello & <world> \"test\" 'quote'"
        val seg = makeSegment(0.0, 1.0, text)
        val srt = buildSrt(listOf(seg))
        assertTrue(srt.contains(text), "特殊字符不应被转义")
    }
}
