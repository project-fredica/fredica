package com.github.project_fredica.asr.model

// =============================================================================
// SrtModelTest
// =============================================================================
//
// 被测对象：SrtTimestamp（value class）+ TranscribeSegment.buildSrt / parseSrt
//
// 测试矩阵：
//   S1. SrtTimestamp.format - 整秒（无毫秒）
//   S2. SrtTimestamp.format - 含毫秒
//   S3. SrtTimestamp.format - 超过 1 小时
//   S4. buildSrt - 空列表 → 空字符串
//   S5. buildSrt - 单段 → 正确序号/时间轴/文本
//   S6. buildSrt - 多段 → 序号递增，段间有空行
//   S7. buildSrt - 文本含特殊字符不被转义
// =============================================================================

import com.github.project_fredica.asr.srt.SrtTimestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SrtModelTest {

    // ── S1 ───────────────────────────────────────────────────────────────────

    @Test
    fun `S1 - SrtTimestamp format whole seconds`() {
        assertEquals("00:00:05,000", SrtTimestamp(5.0).format())
        assertEquals("00:01:00,000", SrtTimestamp(60.0).format())
    }

    // ── S2 ───────────────────────────────────────────────────────────────────

    @Test
    fun `S2 - SrtTimestamp format with milliseconds`() {
        assertEquals("00:00:01,500", SrtTimestamp(1.5).format())
        assertEquals("00:00:00,123", SrtTimestamp(0.123).format())
    }

    // ── S3 ───────────────────────────────────────────────────────────────────

    @Test
    fun `S3 - SrtTimestamp format over one hour`() {
        // 1h 2m 3.456s
        val sec = 3600.0 + 2 * 60.0 + 3.456
        assertEquals("01:02:03,456", SrtTimestamp(sec).format())
    }

    // ── S4 ───────────────────────────────────────────────────────────────────

    @Test
    fun `S4 - buildSrt empty list returns empty string`() {
        assertEquals("", TranscribeSegment.buildSrt(emptyList()).trim())
    }

    // ── S5 ───────────────────────────────────────────────────────────────────

    @Test
    fun `S5 - buildSrt single segment has correct structure`() {
        val seg = TranscribeSegment(0.0, 1.5, "你好世界")
        val srt = TranscribeSegment.buildSrt(listOf(seg))
        val lines = srt.lines().filter { it.isNotBlank() }

        assertEquals("1",                                    lines[0], "序号应为 1")
        assertEquals("00:00:00,000 --> 00:00:01,500",        lines[1], "时间轴格式错误")
        assertEquals("你好世界",                              lines[2], "文本内容错误")
    }

    // ── S6 ───────────────────────────────────────────────────────────────────

    @Test
    fun `S6 - buildSrt multiple segments have incrementing indices and blank separators`() {
        val segs = listOf(
            TranscribeSegment(0.0,  1.0, "第一段"),
            TranscribeSegment(1.0,  2.5, "第二段"),
            TranscribeSegment(2.5,  4.0, "第三段"),
        )
        val srt = TranscribeSegment.buildSrt(segs)

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
        val seg = TranscribeSegment(0.0, 1.0, text)
        val srt = TranscribeSegment.buildSrt(listOf(seg))
        assertTrue(srt.contains(text), "特殊字符不应被转义")
    }
}
