package com.github.project_fredica.asr

// =============================================================================
// MaterialSubtitleServiceTest
// =============================================================================
//
// 被测对象：MaterialSubtitleService（内部工具方法通过 MaterialSubtitleService.Util 访问）
//
// 测试矩阵：
//   S1.  Util.parseSrt - 标准 SRT 文本解析（时间戳、多行文本）
//   S2.  Util.parseSrt - 空文本返回空列表
//   S3.  Util.parseSrt - 时间戳使用句号分隔毫秒（00:00:01.500）
//   S4.  Util.parseSrt - 只有序号和时间戳无内容行 → 跳过
//   S5.  Util.readTranscriptMeta - 正常读取 transcript.meta.json
//   S6.  Util.readTranscriptMeta - 文件不存在返回 null
//   S7.  Util.readTranscriptMeta - 文件内容非法 JSON 返回 null
//   S8.  Util.readChunkMeta - 正常读取 chunk_XXXX.meta.json
//   S9.  Util.readChunkMeta - 文件不存在返回 null
//   S10. Util.readSrtFile - 文件存在返回内容
//   S11. Util.readSrtFile - 文件不存在返回空字符串
//   S12. scanAsrSubtitleItems - 无 asr_results 目录返回空列表
//   S13. scanAsrSubtitleItems - 单模型完整结果
//   S14. scanAsrSubtitleItems - 单模型 partial 结果（有 chunk 无 transcript.done）
//   S15. scanAsrSubtitleItems - 多模型目录返回多条
//   S16. scanAsrSubtitleItems - 空 SRT 文件不产出条目
//   S17. readAsrSubtitleDetail - 完整结果（transcript.done + transcript.srt）
//   S18. readAsrSubtitleDetail - partial 结果（多 chunk 拼接）
//   S19. readAsrSubtitleDetail - 无文件返回空 response
//   S20. readAsrSubtitleDetail - 从 transcript.meta.json 读取 language 和 model_size
//   S21. parseSubtitleId - 标准格式解析
//   S22. parseSubtitleId - first 特殊值
//   S23. parseSubtitleId - 无 dot 返回 unknown
// =============================================================================

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.asr.service.MaterialSubtitleService
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MaterialSubtitleServiceTest {

    private val matId = "test-subtitle-svc-${System.nanoTime()}"
    private lateinit var mediaDir: File

    @BeforeTest
    fun setup() = runBlocking {
        mediaDir = AppUtil.Paths.materialMediaDir(matId)
        mediaDir.mkdirs()
        Unit
    }

    @AfterTest
    fun tearDown() = runBlocking {
        mediaDir.deleteRecursively()
        Unit
    }

    private fun asrDir(model: String = "large-v3"): File =
        mediaDir.resolve("asr_results").resolve(model).also { it.mkdirs() }

    // ── S1 ───────────────────────────────────────────────────────────────────

    @Test
    fun `S1 - parseSrt parses standard SRT with timestamps and multiline text`() {
        val srt = """
1
00:00:01,000 --> 00:00:03,500
你好世界

2
00:00:04,000 --> 00:00:06,200
第二行
多行文本

""".trimIndent()

        val segments = MaterialSubtitleService.Util.parseSrt(srt)
        assertEquals(2, segments.size)
        assertEquals(1.0, segments[0].from)
        assertEquals(3.5, segments[0].to)
        assertEquals("你好世界", segments[0].content)
        assertEquals(4.0, segments[1].from)
        assertEquals(6.2, segments[1].to)
        assertEquals("第二行\n多行文本", segments[1].content)
    }

    // ── S2 ───────────────────────────────────────────────────────────────────

    @Test
    fun `S2 - parseSrt returns empty list for empty text`() {
        assertEquals(0, MaterialSubtitleService.Util.parseSrt("").size)
        assertEquals(0, MaterialSubtitleService.Util.parseSrt("   \n  ").size)
    }

    // ── S3 ───────────────────────────────────────────────────────────────────

    @Test
    fun `S3 - parseSrt handles dot-separated milliseconds`() {
        val srt = """
1
00:00:01.500 --> 00:00:03.200
Hello

""".trimIndent()

        val segments = MaterialSubtitleService.Util.parseSrt(srt)
        assertEquals(1, segments.size)
        assertEquals(1.5, segments[0].from)
        assertEquals(3.2, segments[0].to)
    }

    // ── S4 ───────────────────────────────────────────────────────────────────

    @Test
    fun `S4 - parseSrt skips blocks with no content lines`() {
        val srt = """
1
00:00:01,000 --> 00:00:03,000

""".trimIndent()

        val segments = MaterialSubtitleService.Util.parseSrt(srt)
        assertEquals(0, segments.size)
    }

    // ── S5 ───────────────────────────────────────────────────────────────────

    @Test
    fun `S5 - readTranscriptMeta reads valid meta file`() {
        val dir = asrDir()
        val metaFile = dir.resolve("transcript.meta.json")
        metaFile.writeText("""{"model_size":"large-v3","language":"zh","total_segments":10,"total_chunks":3}""")

        val meta = MaterialSubtitleService.Util.readTranscriptMeta(metaFile)
        assertNotNull(meta)
        assertEquals("large-v3", meta.modelSize)
        assertEquals("zh", meta.language)
        assertEquals(10, meta.totalSegments)
        assertEquals(3, meta.totalChunks)
    }

    // ── S6 ───────────────────────────────────────────────────────────────────

    @Test
    fun `S6 - readTranscriptMeta returns null for missing file`() {
        val missing = File(mediaDir, "nonexistent.json")
        assertNull(MaterialSubtitleService.Util.readTranscriptMeta(missing))
    }

    // ── S7 ───────────────────────────────────────────────────────────────────

    @Test
    fun `S7 - readTranscriptMeta returns null for invalid JSON`() {
        val dir = asrDir()
        val metaFile = dir.resolve("transcript.meta.json")
        metaFile.writeText("not valid json {{{")

        assertNull(MaterialSubtitleService.Util.readTranscriptMeta(metaFile))
    }

    // ── S8 ───────────────────────────────────────────────────────────────────

    @Test
    fun `S8 - readChunkMeta reads valid chunk meta file`() {
        val dir = asrDir()
        val metaFile = dir.resolve("chunk_0000.meta.json")
        metaFile.writeText("""{"model_size":"medium","language":"en","segment_count":5}""")

        val meta = MaterialSubtitleService.Util.readChunkMeta(metaFile)
        assertNotNull(meta)
        assertEquals("medium", meta.modelSize)
        assertEquals("en", meta.language)
        assertEquals(5, meta.segmentCount)
    }

    // ── S9 ───────────────────────────────────────────────────────────────────

    @Test
    fun `S9 - readChunkMeta returns null for missing file`() {
        assertNull(MaterialSubtitleService.Util.readChunkMeta(File(mediaDir, "no.json")))
    }

    // ── S10 ──────────────────────────────────────────────────────────────────

    @Test
    fun `S10 - readSrtFile returns file content when file exists`() {
        val dir = asrDir("large-v3")
        val srtFile = dir.resolve("transcript.srt")
        srtFile.writeText("1\n00:00:00,000 --> 00:00:01,000\nHello\n\n")

        val content = MaterialSubtitleService.Util.readSrtFile(srtFile)
        assertTrue(content.contains("Hello"))
    }

    // ── S11 ──────────────────────────────────────────────────────────────────

    @Test
    fun `S11 - readSrtFile returns empty string when file missing`() {
        val content = MaterialSubtitleService.Util.readSrtFile(File("/nonexistent/path/file.srt"))
        assertEquals("", content)
    }

    // ── S12 ──────────────────────────────────────────────────────────────────

    @Test
    fun `S12 - scanAsrSubtitleItems returns empty when no asr_results dir`() = runBlocking {
        val items = MaterialSubtitleService.scanAsrSubtitleItems(matId)
        assertEquals(0, items.size)
        Unit
    }

    // ── S13 ──────────────────────────────────────────────────────────────────

    @Test
    fun `S13 - scanAsrSubtitleItems returns complete item for finished transcript`() = runBlocking {
        val dir = asrDir("large-v3")
        dir.resolve("transcript.srt").writeText("1\n00:00:00,000 --> 00:00:01,000\n你好\n\n")
        dir.resolve("transcript.done").writeText("{}")

        val items = MaterialSubtitleService.scanAsrSubtitleItems(matId)
        assertEquals(1, items.size)
        assertEquals("large-v3", items[0].modelSize)
        assertFalse(items[0].partial)
        assertTrue(items[0].subtitleUrl.endsWith("transcript.srt"))
        Unit
    }

    // ── S14 ──────────────────────────────────────────────────────────────────

    @Test
    fun `S14 - scanAsrSubtitleItems returns partial item for chunk-only results`() = runBlocking {
        val dir = asrDir("large-v3")
        dir.resolve("chunk_0000.srt").writeText("1\n00:00:00,000 --> 00:00:01,000\n测试\n\n")

        val items = MaterialSubtitleService.scanAsrSubtitleItems(matId)
        assertEquals(1, items.size)
        assertTrue(items[0].partial)
        assertTrue(items[0].lanDoc.contains("进行中"))
        Unit
    }

    // ── S15 ──────────────────────────────────────────────────────────────────

    @Test
    fun `S15 - scanAsrSubtitleItems returns items for multiple model dirs`() = runBlocking {
        for (model in listOf("large-v3", "medium")) {
            val dir = asrDir(model)
            dir.resolve("transcript.srt").writeText("1\n00:00:00,000 --> 00:00:01,000\ntest\n\n")
            dir.resolve("transcript.done").writeText("{}")
        }

        val items = MaterialSubtitleService.scanAsrSubtitleItems(matId)
        assertEquals(2, items.size)
        val sizes = items.map { it.modelSize }.toSet()
        assertTrue(sizes.contains("large-v3"))
        assertTrue(sizes.contains("medium"))
        Unit
    }

    // ── S16 ──────────────────────────────────────────────────────────────────

    @Test
    fun `S16 - scanAsrSubtitleItems skips empty SRT files`() = runBlocking {
        val dir = asrDir("large-v3")
        dir.resolve("transcript.srt").writeText("")
        dir.resolve("transcript.done").writeText("{}")

        val items = MaterialSubtitleService.scanAsrSubtitleItems(matId)
        assertEquals(0, items.size)
        Unit
    }

    // ── S17 ──────────────────────────────────────────────────────────────────

    @Test
    fun `S17 - readAsrSubtitleDetail returns complete result`() = runBlocking {
        val dir = asrDir("large-v3")
        dir.resolve("transcript.srt").writeText(
            "1\n00:00:00,000 --> 00:00:02,000\n你好\n\n2\n00:00:02,500 --> 00:00:05,000\n世界\n\n"
        )
        dir.resolve("transcript.done").writeText("{}")

        val resp = MaterialSubtitleService.readAsrSubtitleDetail(matId, "large-v3")
        assertFalse(resp.partial)
        assertEquals(2, resp.segments.size)
        assertEquals(2, resp.segmentCount)
        assertEquals(0.0, resp.segments[0].from)
        assertEquals("你好", resp.segments[0].content)
        Unit
    }

    // ── S18 ──────────────────────────────────────────────────────────────────

    @Test
    fun `S18 - readAsrSubtitleDetail returns partial result from chunks`() = runBlocking {
        val dir = asrDir("large-v3")
        dir.resolve("chunk_0000.srt").writeText("1\n00:00:00,000 --> 00:00:01,000\n第一段\n\n")
        dir.resolve("chunk_0001.srt").writeText("1\n00:00:10,000 --> 00:00:12,000\n第二段\n\n")
        dir.resolve("chunk_0000.done").writeText("{}")

        val resp = MaterialSubtitleService.readAsrSubtitleDetail(matId, "large-v3")
        assertTrue(resp.partial)
        assertEquals(2, resp.segments.size)
        assertEquals("第一段", resp.segments[0].content)
        assertEquals("第二段", resp.segments[1].content)
        assertEquals(1, resp.doneChunks)
        Unit
    }

    // ── S19 ──────────────────────────────────────────────────────────────────

    @Test
    fun `S19 - readAsrSubtitleDetail returns empty response when no files`() = runBlocking {
        asrDir("large-v3") // create empty dir

        val resp = MaterialSubtitleService.readAsrSubtitleDetail(matId, "large-v3")
        assertEquals(0, resp.segments.size)
        assertFalse(resp.partial)
        Unit
    }

    // ── S20 ──────────────────────────────────────────────────────────────────

    @Test
    fun `S20 - readAsrSubtitleDetail reads language and model_size from meta`() = runBlocking {
        val dir = asrDir("large-v3")
        dir.resolve("transcript.srt").writeText("1\n00:00:00,000 --> 00:00:01,000\ntest\n\n")
        dir.resolve("transcript.done").writeText("{}")
        dir.resolve("transcript.meta.json").writeText(
            """{"model_size":"large-v3","language":"zh","total_segments":1,"total_chunks":1}"""
        )

        val resp = MaterialSubtitleService.readAsrSubtitleDetail(matId, "large-v3")
        assertEquals("large-v3", resp.modelSize)
        assertEquals("zh", resp.language)
        Unit
    }

    // ── S21 ──────────────────────────────────────────────────────────────────

    @Test
    fun `S21 - parseSubtitleId parses standard source dot key format`() {
        assertEquals("bili" to "ai-zh", MaterialSubtitleService.parseSubtitleId("bili.ai-zh"))
        assertEquals("asr" to "large-v3", MaterialSubtitleService.parseSubtitleId("asr.large-v3"))
        assertEquals("pp" to "pp_1712000000_a3f2", MaterialSubtitleService.parseSubtitleId("pp.pp_1712000000_a3f2"))
    }

    // ── S22 ──────────────────────────────────────────────────────────────────

    @Test
    fun `S22 - parseSubtitleId handles first special value`() {
        assertEquals("first" to "first", MaterialSubtitleService.parseSubtitleId("first"))
    }

    // ── S23 ──────────────────────────────────────────────────────────────────

    @Test
    fun `S23 - parseSubtitleId returns unknown source when no dot`() {
        val (source, key) = MaterialSubtitleService.parseSubtitleId("nodot")
        assertEquals("unknown", source)
        assertEquals("nodot", key)
    }
}
