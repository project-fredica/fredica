package com.github.project_fredica.api.routes

// =============================================================================
// MaterialAsrSubtitleRouteTest
// =============================================================================
//
// 被测对象：MaterialAsrSubtitleRoute.handler() + parseSrt()
//
// 测试矩阵：
//   A1. parseSrt - 标准 SRT 文本解析（时间戳、多行文本）
//   A2. parseSrt - 空文本返回空列表
//   A3. parseSrt - 时间戳使用句号分隔毫秒（00:00:01.500）
//   A4. handler - 无文件时返回空 segments
//   A5. handler - transcript.done + transcript.srt 存在 → 完整结果
//   A6. handler - 仅 chunk_0000.srt 无 transcript.done → partial=true
//   A7. handler - 多 chunk 拼接（chunk_0000.srt + chunk_0001.srt）
//   A8. handler - transcript.meta.json 提供 model_size 和 language
//   A9. handler - material_id 缺失时返回空 response
//   A10. handler - model_size=medium 查询不同模型目录
//   A11. handler - 不带 model_size 参数默认查 large-v3
// =============================================================================

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.asr.model.AsrSubtitleResponse
import com.github.project_fredica.asr.service.MaterialSubtitleService
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MaterialAsrSubtitleRouteTest {

    private val matId = "test-asr-subtitle-${System.nanoTime()}"
    private lateinit var asrDir: File

    @BeforeTest
    fun setup() = runBlocking {
        val mediaDir = AppUtil.Paths.materialMediaDir(matId)
        asrDir = mediaDir.resolve("asr_results").resolve("large-v3").also { it.mkdirs() }
    }

    @AfterTest
    fun tearDown() = runBlocking {
        AppUtil.Paths.materialMediaDir(matId).deleteRecursively()
        Unit
    }

    private fun queryParam(id: String = matId) = """{"material_id":["$id"]}"""

    private suspend fun callHandler(id: String = matId): AsrSubtitleResponse {
        val result = MaterialAsrSubtitleRoute.handler(queryParam(id))
        return result.str.loadJsonModel<AsrSubtitleResponse>().getOrThrow()
    }

    // ── A1 ───────────────────────────────────────────────────────────────────

    @Test
    fun `A1 - parseSrt parses standard SRT with timestamps and multiline text`() {
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

    // ── A2 ───────────────────────────────────────────────────────────────────

    @Test
    fun `A2 - parseSrt returns empty list for empty text`() {
        assertEquals(0, MaterialSubtitleService.Util.parseSrt("").size)
        assertEquals(0, MaterialSubtitleService.Util.parseSrt("   \n  ").size)
    }

    // ── A3 ───────────────────────────────────────────────────────────────────

    @Test
    fun `A3 - parseSrt handles dot-separated milliseconds`() {
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

    // ── A4 ───────────────────────────────────────────────────────────────────

    @Test
    fun `A4 - handler returns empty segments when no files exist`() = runBlocking {
        // asrDir exists but is empty
        val resp = callHandler()
        assertEquals(0, resp.segments.size)
        assertEquals(0, resp.segmentCount)
        assertFalse(resp.partial)
        Unit
    }

    // ── A5 ───────────────────────────────────────────────────────────────────

    @Test
    fun `A5 - handler returns complete result when transcript done and srt exist`() = runBlocking {
        val srt = """
1
00:00:00,000 --> 00:00:02,000
你好

2
00:00:02,500 --> 00:00:05,000
世界

""".trimIndent()
        asrDir.resolve("transcript.srt").writeText(srt)
        asrDir.resolve("transcript.done").writeText("{}")

        val resp = callHandler()
        assertFalse(resp.partial)
        assertEquals(2, resp.segments.size)
        assertEquals(2, resp.segmentCount)
        assertEquals(0.0, resp.segments[0].from)
        assertEquals(2.0, resp.segments[0].to)
        assertEquals("你好", resp.segments[0].content)
        assertEquals(2.5, resp.segments[1].from)
        Unit
    }

    // ── A6 ───────────────────────────────────────────────────────────────────

    @Test
    fun `A6 - handler returns partial when only chunk srt exists without transcript done`() = runBlocking {
        val srt = """
1
00:00:00,000 --> 00:00:01,000
测试

""".trimIndent()
        asrDir.resolve("chunk_0000.srt").writeText(srt)

        val resp = callHandler()
        assertTrue(resp.partial)
        assertEquals(1, resp.segments.size)
        assertEquals("测试", resp.segments[0].content)
        assertEquals(0, resp.doneChunks, "无 .done 文件时 doneChunks=0")
        Unit
    }

    // ── A7 ───────────────────────────────────────────────────────────────────

    @Test
    fun `A7 - handler concatenates multiple chunk srts in order`() = runBlocking {
        val srt0 = """
1
00:00:00,000 --> 00:00:01,000
第一段

""".trimIndent()
        val srt1 = """
1
00:00:10,000 --> 00:00:12,000
第二段

""".trimIndent()
        asrDir.resolve("chunk_0000.srt").writeText(srt0)
        asrDir.resolve("chunk_0001.srt").writeText(srt1)
        asrDir.resolve("chunk_0000.done").writeText("{}")

        val resp = callHandler()
        assertTrue(resp.partial)
        assertEquals(2, resp.segments.size)
        assertEquals("第一段", resp.segments[0].content)
        assertEquals("第二段", resp.segments[1].content)
        assertEquals(0.0, resp.segments[0].from)
        assertEquals(10.0, resp.segments[1].from)
        assertEquals(1, resp.doneChunks, "只有 chunk_0000.done")
        Unit
    }

    // ── A8 ───────────────────────────────────────────────────────────────────

    @Test
    fun `A8 - handler reads model_size and language from transcript meta`() = runBlocking {
        val srt = """
1
00:00:00,000 --> 00:00:01,000
test

""".trimIndent()
        asrDir.resolve("transcript.srt").writeText(srt)
        asrDir.resolve("transcript.done").writeText("{}")
        asrDir.resolve("transcript.meta.json").writeText(
            """{"model_size":"large-v3","language":"zh","total_segments":1,"total_chunks":1}"""
        )

        val resp = callHandler()
        assertEquals("large-v3", resp.modelSize)
        assertEquals("zh", resp.language)
        assertFalse(resp.partial)
        Unit
    }

    // ── A9 ───────────────────────────────────────────────────────────────────

    @Test
    fun `A9 - handler returns empty response when material_id is missing`() = runBlocking {
        val result = MaterialAsrSubtitleRoute.handler("""{}""")
        val resp = result.str.loadJsonModel<AsrSubtitleResponse>().getOrThrow()
        assertEquals(0, resp.segments.size)
        assertFalse(resp.partial)
        Unit
    }

    // ── A10 ──────────────────────────────────────────────────────────────────

    private fun queryParamWithModel(id: String = matId, modelSize: String) =
        """{"material_id":["$id"],"model_size":["$modelSize"]}"""

    private suspend fun callHandlerWithModel(id: String = matId, modelSize: String): AsrSubtitleResponse {
        val result = MaterialAsrSubtitleRoute.handler(queryParamWithModel(id, modelSize))
        return result.str.loadJsonModel<AsrSubtitleResponse>().getOrThrow()
    }

    @Test
    fun `A10 - handler with model_size=medium reads from medium directory`() = runBlocking {
        val mediumDir = AppUtil.Paths.materialMediaDir(matId).resolve("asr_results").resolve("medium").also { it.mkdirs() }
        val srt = """
1
00:00:00,000 --> 00:00:01,000
medium test

""".trimIndent()
        mediumDir.resolve("transcript.srt").writeText(srt)
        mediumDir.resolve("transcript.done").writeText("{}")

        val resp = callHandlerWithModel(modelSize = "medium")
        assertFalse(resp.partial)
        assertEquals(1, resp.segments.size)
        assertEquals("medium test", resp.segments[0].content)
        Unit
    }

    // ── A11 ──────────────────────────────────────────────────────────────────

    @Test
    fun `A11 - handler without model_size defaults to large-v3`() = runBlocking {
        // asrDir is already asr_results/large-v3 from setup
        val srt = """
1
00:00:00,000 --> 00:00:02,000
default model

""".trimIndent()
        asrDir.resolve("transcript.srt").writeText(srt)
        asrDir.resolve("transcript.done").writeText("{}")

        // Call without model_size param
        val resp = callHandler()
        assertFalse(resp.partial)
        assertEquals(1, resp.segments.size)
        assertEquals("default model", resp.segments[0].content)
        Unit
    }
}
