package com.github.project_fredica.worker.executors

// =============================================================================
// TranscribeExecutorFileTest
// =============================================================================
//
// 被测对象：TranscribeExecutor 的 per-chunk 文件操作辅助函数
//
// 策略：通过反射调用私有方法，不依赖 Python / WebSocket / DB。
//       使用临时目录模拟 asr_result 文件结构。
//
// 测试矩阵：
//   F1. backupJsonl - 有 start_info.json 时使用其 started_at 作为备份文件名
//   F2. backupJsonl - 无 start_info.json 时使用当前时间戳
//   F3. backupJsonl - 旧 .jsonl 被重命名，原位置不再存在
//   F4. tryMergeChunks - 单 chunk 完成后合并生成 transcript.srt + meta + done
//   F5. tryMergeChunks - 多 chunk 合并，segments 按顺序拼接并重新编号
//   F6. tryMergeChunks - 未全部完成时不合并
//   F7. sha256String - 相同输入产生相同哈希
//   F8. sha256String - 不同输入产生不同哈希
//   F9. writeChunkDone - 写入的 done 文件包含正确的 input_hash 和 output_hash
//   F10. chunkPrefix - 格式化为 chunk_XXXX
//   F11. isoToFilenameSafe - 冒号替换为短横线
//   F12. tryMergeChunks - core region 中点过滤
//   F13. tryMergeChunks - 最后一个 chunk 使用闭区间
//   F14. tryMergeChunks - 无 core region 时保留全部（向后兼容）
//   F15. canSkip - 缓存命中（done 文件 + hash/model/language 匹配）
//   F16. canSkip - 缓存失效：hash 不匹配
//   F17. canSkip - 缓存失效：model_size 不匹配
//   F18. canSkip - 缓存失效：language 不匹配
//   F19. canSkip - 缓存失效：done 文件不存在
//   F20. canSkip - 缓存失效：音频文件不存在
// =============================================================================

import com.github.project_fredica.asr.executor.TranscribeExecutor
import com.github.project_fredica.asr.model.TranscribeSegment
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskPriority
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TranscribeExecutorFileTest {

    private lateinit var tmpDir: File

    @BeforeTest
    fun setup() {
        tmpDir = File.createTempFile("transcribe_file_test_", "").also {
            it.delete()
            it.mkdirs()
        }
    }

    @AfterTest
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    // ── 反射辅助 ──────────────────────────────────────────────────────────────

    private fun callChunkPrefix(index: Int): String {
        val m = TranscribeExecutor::class.java
            .getDeclaredMethod("chunkPrefix", Int::class.javaPrimitiveType)
            .also { it.isAccessible = true }
        return m.invoke(TranscribeExecutor, index) as String
    }

    private fun callIsoToFilenameSafe(iso: String): String {
        val m = TranscribeExecutor::class.java
            .getDeclaredMethod("isoToFilenameSafe", String::class.java)
            .also { it.isAccessible = true }
        return m.invoke(TranscribeExecutor, iso) as String
    }

    private fun callSha256String(text: String): String {
        val m = TranscribeExecutor::class.java
            .getDeclaredMethod("sha256String", String::class.java)
            .also { it.isAccessible = true }
        return m.invoke(TranscribeExecutor, text) as String
    }

    private fun callBackupJsonl(outDir: File, prefix: String, jsonlFile: File) {
        val m = TranscribeExecutor::class.java
            .getDeclaredMethod("backupJsonl", File::class.java, String::class.java, File::class.java)
            .also { it.isAccessible = true }
        m.invoke(TranscribeExecutor, outDir, prefix, jsonlFile)
    }

    private fun callTryMergeChunks(outDir: File, totalChunks: Int, modelSize: String, language: String) {
        val m = TranscribeExecutor::class.java
            .getDeclaredMethod("tryMergeChunks", File::class.java, Int::class.javaPrimitiveType, String::class.java, String::class.java)
            .also { it.isAccessible = true }
        m.invoke(TranscribeExecutor, outDir, totalChunks, modelSize, language)
    }

    private fun callWriteChunkDone(outDir: File, prefix: String, audioFile: File, segments: List<TranscribeSegment>, modelSize: String, language: String?) {
        val m = TranscribeExecutor::class.java
            .getDeclaredMethod("writeChunkDone", File::class.java, String::class.java, File::class.java, List::class.java, String::class.java, String::class.java)
            .also { it.isAccessible = true }
        m.invoke(TranscribeExecutor, outDir, prefix, audioFile, segments, modelSize, language)
    }

    // ── F1 ───────────────────────────────────────────────────────────────────

    @Test
    fun `F1 - backupJsonl uses started_at from start_info json`() {
        val prefix = "chunk_0000"
        val jsonlFile = tmpDir.resolve("$prefix.jsonl").also { it.writeText("old data\n") }
        // 写入 start_info.json
        tmpDir.resolve("$prefix.start_info.json").writeText(
            """{"started_at":"2026-04-09T14:30:05","audio_path":"/a.m4a","model_size":"large-v3","language":null,"compute_type":"float16"}"""
        )

        callBackupJsonl(tmpDir, prefix, jsonlFile)

        // 旧文件应被重命名
        assertFalse(jsonlFile.exists(), "原 .jsonl 应被重命名")
        val backupFile = tmpDir.resolve("chunk_0000.2026-04-09T14-30-05.bck.jsonl")
        assertTrue(backupFile.exists(), "备份文件应存在: ${backupFile.name}")
        assertEquals("old data\n", backupFile.readText())
    }

    // ── F2 ───────────────────────────────────────────────────────────────────

    @Test
    fun `F2 - backupJsonl uses current timestamp when no start_info exists`() {
        val prefix = "chunk_0000"
        val jsonlFile = tmpDir.resolve("$prefix.jsonl").also { it.writeText("data\n") }
        // 不写 start_info.json

        callBackupJsonl(tmpDir, prefix, jsonlFile)

        assertFalse(jsonlFile.exists(), "原 .jsonl 应被重命名")
        // 应有一个 .bck.jsonl 文件
        val backups = tmpDir.listFiles()?.filter { it.name.endsWith(".bck.jsonl") } ?: emptyList()
        assertEquals(1, backups.size, "应有 1 个备份文件")
        assertTrue(backups[0].name.startsWith("chunk_0000."), "备份文件名应以 chunk_0000. 开头")
        assertTrue(backups[0].name.contains(".bck.jsonl"), "备份文件名应包含 .bck.jsonl")
    }

    // ── F3 ───────────────────────────────────────────────────────────────────

    @Test
    fun `F3 - backupJsonl removes original file after rename`() {
        val prefix = "chunk_0001"
        val jsonlFile = tmpDir.resolve("$prefix.jsonl").also { it.writeText("content\n") }
        tmpDir.resolve("$prefix.start_info.json").writeText(
            """{"started_at":"2026-01-01T00:00:00","audio_path":"/a.m4a","model_size":"m","language":null,"compute_type":"f16"}"""
        )

        callBackupJsonl(tmpDir, prefix, jsonlFile)

        assertFalse(jsonlFile.exists(), "原 .jsonl 不应存在")
        val expected = tmpDir.resolve("chunk_0001.2026-01-01T00-00-00.bck.jsonl")
        assertTrue(expected.exists(), "备份文件应存在")
        assertEquals("content\n", expected.readText())
    }

    // ── F4 ───────────────────────────────────────────────────────────────────

    @Test
    fun `F4 - tryMergeChunks single chunk produces transcript files`() {
        // 准备 chunk_0000 的 SRT 和 done 文件
        val srt = "1\n00:00:00,000 --> 00:00:02,000\n你好\n\n"
        tmpDir.resolve("chunk_0000.srt").writeText(srt)
        tmpDir.resolve("chunk_0000.done").writeText("{}")

        callTryMergeChunks(tmpDir, 1, "large-v3", "zh")

        assertTrue(tmpDir.resolve("transcript.srt").exists(), "transcript.srt 应存在")
        assertTrue(tmpDir.resolve("transcript.meta.json").exists(), "transcript.meta.json 应存在")
        assertTrue(tmpDir.resolve("transcript.done").exists(), "transcript.done 应存在")

        // 验证 meta 内容
        val meta = Json.parseToJsonElement(tmpDir.resolve("transcript.meta.json").readText()) as JsonObject
        assertEquals("large-v3", meta["model_size"]?.jsonPrimitive?.content)
        assertEquals("zh", meta["language"]?.jsonPrimitive?.content)
        assertEquals("1", meta["total_segments"]?.jsonPrimitive?.content)
        assertEquals("1", meta["total_chunks"]?.jsonPrimitive?.content)

        // 验证 done 内容
        val done = Json.parseToJsonElement(tmpDir.resolve("transcript.done").readText()) as JsonObject
        assertEquals("1", done["chunk_count"]?.jsonPrimitive?.content)
        assertTrue(done["output_hash"]?.jsonPrimitive?.content?.isNotBlank() == true, "output_hash 应非空")
    }

    // ── F5 ───────────────────────────────────────────────────────────────────

    @Test
    fun `F5 - tryMergeChunks multiple chunks concatenates and renumbers`() {
        val srt0 = "1\n00:00:00,000 --> 00:00:01,000\n第一段\n\n"
        val srt1 = "1\n00:00:10,000 --> 00:00:12,000\n第二段\n\n"
        tmpDir.resolve("chunk_0000.srt").writeText(srt0)
        tmpDir.resolve("chunk_0001.srt").writeText(srt1)
        tmpDir.resolve("chunk_0000.done").writeText("{}")
        tmpDir.resolve("chunk_0001.done").writeText("{}")

        callTryMergeChunks(tmpDir, 2, "medium", "en")

        assertTrue(tmpDir.resolve("transcript.srt").exists())
        val merged = tmpDir.resolve("transcript.srt").readText()

        // 应包含两段，序号重新编号为 1 和 2
        assertTrue(merged.contains("第一段"), "应包含第一段")
        assertTrue(merged.contains("第二段"), "应包含第二段")

        // 验证序号重新编号
        val lines = merged.lines()
        val seqNumbers = lines.filter { it.trim().matches(Regex("^\\d+$")) }
        assertEquals(listOf("1", "2"), seqNumbers, "序号应为 1, 2")

        // 验证 meta
        val meta = Json.parseToJsonElement(tmpDir.resolve("transcript.meta.json").readText()) as JsonObject
        assertEquals("2", meta["total_segments"]?.jsonPrimitive?.content)
        assertEquals("2", meta["total_chunks"]?.jsonPrimitive?.content)
    }

    // ── F6 ───────────────────────────────────────────────────────────────────

    @Test
    fun `F6 - tryMergeChunks does not merge when not all chunks done`() {
        val srt0 = "1\n00:00:00,000 --> 00:00:01,000\n段落\n\n"
        tmpDir.resolve("chunk_0000.srt").writeText(srt0)
        tmpDir.resolve("chunk_0000.done").writeText("{}")
        // chunk_0001 没有 .done 文件

        callTryMergeChunks(tmpDir, 2, "large-v3", "zh")

        assertFalse(tmpDir.resolve("transcript.srt").exists(), "不应生成 transcript.srt")
        assertFalse(tmpDir.resolve("transcript.meta.json").exists(), "不应生成 transcript.meta.json")
        assertFalse(tmpDir.resolve("transcript.done").exists(), "不应生成 transcript.done")
    }

    // ── F7 ───────────────────────────────────────────────────────────────────

    @Test
    fun `F7 - sha256String same input produces same hash`() {
        val hash1 = callSha256String("hello world")
        val hash2 = callSha256String("hello world")
        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length, "SHA-256 hex 应为 64 字符")
    }

    // ── F8 ───────────────────────────────────────────────────────────────────

    @Test
    fun `F8 - sha256String different input produces different hash`() {
        val hash1 = callSha256String("hello")
        val hash2 = callSha256String("world")
        assertNotEquals(hash1, hash2)
    }

    // ── F9 ───────────────────────────────────────────────────────────────────

    @Test
    fun `F9 - writeChunkDone produces correct input_hash and output_hash`() {
        val audioFile = tmpDir.resolve("test_audio.m4a").also { it.writeText("fake audio content") }
        val segments = listOf(
            TranscribeSegment(0.0, 1.0, "test"),
        )

        callWriteChunkDone(tmpDir, "chunk_0000", audioFile, segments, "large-v3", "zh")

        val doneFile = tmpDir.resolve("chunk_0000.done")
        assertTrue(doneFile.exists(), "chunk_0000.done 应存在")

        val done = Json.parseToJsonElement(doneFile.readText()) as JsonObject
        val inputHash = done["input_hash"]?.jsonPrimitive?.content ?: ""
        val outputHash = done["output_hash"]?.jsonPrimitive?.content ?: ""
        val modelSize = done["model_size"]?.jsonPrimitive?.content ?: ""
        val language = done["language"]?.jsonPrimitive?.content

        assertTrue(inputHash.isNotBlank(), "input_hash 应非空")
        assertTrue(outputHash.isNotBlank(), "output_hash 应非空")
        assertEquals(64, inputHash.length, "input_hash 应为 SHA-256 hex (64 chars)")
        assertEquals(64, outputHash.length, "output_hash 应为 SHA-256 hex (64 chars)")
        assertEquals("large-v3", modelSize)
        assertEquals("zh", language)

        // input_hash 应与直接计算音频文件 SHA-256 一致
        val expectedInputHash = callSha256String("fake audio content")
        // 注意：sha256(File) 和 sha256String(String) 对同一内容应产生相同结果
        // 但 sha256(File) 读取字节流，sha256String 读取 UTF-8 字节，对纯 ASCII 文本结果相同
        assertEquals(expectedInputHash, inputHash, "input_hash 应与音频文件 SHA-256 一致")
    }

    // ── F10 ──────────────────────────────────────────────────────────────────

    @Test
    fun `F10 - chunkPrefix formats as chunk_XXXX`() {
        assertEquals("chunk_0000", callChunkPrefix(0))
        assertEquals("chunk_0001", callChunkPrefix(1))
        assertEquals("chunk_0099", callChunkPrefix(99))
        assertEquals("chunk_1234", callChunkPrefix(1234))
    }

    // ── F11 ──────────────────────────────────────────────────────────────────

    @Test
    fun `F11 - isoToFilenameSafe replaces colons with dashes`() {
        assertEquals("2026-04-09T14-30-05", callIsoToFilenameSafe("2026-04-09T14:30:05"))
        assertEquals("2026-01-01T00-00-00", callIsoToFilenameSafe("2026-01-01T00:00:00"))
        // 无冒号的字符串不变
        assertEquals("no-colons-here", callIsoToFilenameSafe("no-colons-here"))
    }

    // ── F12 ──────────────────────────────────────────────────────────────────

    private fun writeSrtFile(dir: File, name: String, segments: List<Triple<Double, Double, String>>) {
        val srt = buildString {
            segments.forEachIndexed { i, (start, end, text) ->
                appendLine(i + 1)
                appendLine("${formatTime(start)} --> ${formatTime(end)}")
                appendLine(text)
                appendLine()
            }
        }
        dir.resolve(name).writeText(srt)
    }

    private fun formatTime(sec: Double): String {
        val totalMs = (sec * 1000).toLong()
        val ms = totalMs % 1000
        val s = (totalMs / 1000) % 60
        val m = (totalMs / 60000) % 60
        val h = totalMs / 3600000
        return "%02d:%02d:%02d,%03d".format(h, m, s, ms)
    }

    private fun writeChunkMeta(dir: File, prefix: String, coreStart: Double, coreEnd: Double) {
        dir.resolve("$prefix.meta.json").writeText(
            buildJsonObject {
                put("language", "zh")
                put("model_size", "large-v3")
                put("segment_count", 0)
                put("completed_at", "2026-01-01T00:00:00")
                put("core_start_sec", coreStart)
                put("core_end_sec", coreEnd)
            }.toString()
        )
    }

    @Test
    fun `F12 - tryMergeChunks core region midpoint filtering`() {
        // chunk 0: core [0, 300), segments at t=50, 250, 310(padding)
        writeSrtFile(tmpDir, "chunk_0000.srt", listOf(
            Triple(40.0, 60.0, "seg-50"),     // midpoint=50, in [0,300)
            Triple(240.0, 260.0, "seg-250"),   // midpoint=250, in [0,300)
            Triple(300.0, 320.0, "seg-310"),   // midpoint=310, NOT in [0,300)
        ))
        tmpDir.resolve("chunk_0000.done").writeText("{}")
        writeChunkMeta(tmpDir, "chunk_0000", 0.0, 300.0)

        // chunk 1: core [300, 600), segments at t=290(padding), 350, 550, 610(padding)
        writeSrtFile(tmpDir, "chunk_0001.srt", listOf(
            Triple(280.0, 300.0, "seg-290"),   // midpoint=290, NOT in [300,600)
            Triple(340.0, 360.0, "seg-350"),   // midpoint=350, in [300,600)
            Triple(540.0, 560.0, "seg-550"),   // midpoint=550, in [300,600)
            Triple(600.0, 620.0, "seg-610"),   // midpoint=610, NOT in [300,600)
        ))
        tmpDir.resolve("chunk_0001.done").writeText("{}")
        writeChunkMeta(tmpDir, "chunk_0001", 300.0, 600.0)

        // chunk 2 (last): core [600, 900], segments at t=590(padding), 650, 850
        writeSrtFile(tmpDir, "chunk_0002.srt", listOf(
            Triple(580.0, 600.0, "seg-590"),   // midpoint=590, NOT in [600,900]
            Triple(640.0, 660.0, "seg-650"),   // midpoint=650, in [600,900]
            Triple(840.0, 860.0, "seg-850"),   // midpoint=850, in [600,900]
        ))
        tmpDir.resolve("chunk_0002.done").writeText("{}")
        writeChunkMeta(tmpDir, "chunk_0002", 600.0, 900.0)

        callTryMergeChunks(tmpDir, 3, "large-v3", "zh")

        assertTrue(tmpDir.resolve("transcript.srt").exists(), "transcript.srt 应存在")
        val merged = tmpDir.resolve("transcript.srt").readText()

        // 应保留 6 个 segments: seg-50, seg-250, seg-350, seg-550, seg-650, seg-850
        assertTrue(merged.contains("seg-50"), "应包含 seg-50")
        assertTrue(merged.contains("seg-250"), "应包含 seg-250")
        assertTrue(merged.contains("seg-350"), "应包含 seg-350")
        assertTrue(merged.contains("seg-550"), "应包含 seg-550")
        assertTrue(merged.contains("seg-650"), "应包含 seg-650")
        assertTrue(merged.contains("seg-850"), "应包含 seg-850")

        // 不应包含 padding segments
        assertFalse(merged.contains("seg-310"), "不应包含 seg-310 (padding)")
        assertFalse(merged.contains("seg-290"), "不应包含 seg-290 (padding)")
        assertFalse(merged.contains("seg-610"), "不应包含 seg-610 (padding)")
        assertFalse(merged.contains("seg-590"), "不应包含 seg-590 (padding)")

        // 验证序号重新编号
        val seqNumbers = merged.lines().filter { it.trim().matches(Regex("^\\d+$")) }
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), seqNumbers, "序号应为 1-6")

        // 验证 meta
        val meta = Json.parseToJsonElement(tmpDir.resolve("transcript.meta.json").readText()) as JsonObject
        assertEquals("6", meta["total_segments"]?.jsonPrimitive?.content)
        assertEquals("3", meta["total_chunks"]?.jsonPrimitive?.content)
    }

    // ── F13 ──────────────────────────────────────────────────────────────────

    @Test
    fun `F13 - tryMergeChunks last chunk uses closed interval`() {
        // 单 chunk（即最后一个 chunk），core [0, 10]
        // segment midpoint 恰好等于 core_end=10 → 应保留（闭区间）
        writeSrtFile(tmpDir, "chunk_0000.srt", listOf(
            Triple(5.0, 7.0, "inside"),        // midpoint=6, in [0,10]
            Triple(9.0, 11.0, "boundary"),     // midpoint=10, == core_end → 保留（闭区间）
            Triple(11.0, 13.0, "outside"),     // midpoint=12, > 10 → 过滤
        ))
        tmpDir.resolve("chunk_0000.done").writeText("{}")
        writeChunkMeta(tmpDir, "chunk_0000", 0.0, 10.0)

        callTryMergeChunks(tmpDir, 1, "large-v3", "zh")

        val merged = tmpDir.resolve("transcript.srt").readText()
        assertTrue(merged.contains("inside"), "应包含 inside")
        assertTrue(merged.contains("boundary"), "midpoint==core_end 应保留（最后 chunk 闭区间）")
        assertFalse(merged.contains("outside"), "midpoint>core_end 应过滤")

        val seqNumbers = merged.lines().filter { it.trim().matches(Regex("^\\d+$")) }
        assertEquals(listOf("1", "2"), seqNumbers)
    }

    // ── F14 ──────────────────────────────────────────────────────────────────

    @Test
    fun `F14 - tryMergeChunks no core region keeps all segments`() {
        // 无 .meta.json → 保留全部 segments（向后兼容）
        writeSrtFile(tmpDir, "chunk_0000.srt", listOf(
            Triple(0.0, 1.0, "first"),
            Triple(290.0, 310.0, "overlap"),
        ))
        tmpDir.resolve("chunk_0000.done").writeText("{}")
        // 不写 chunk_0000.meta.json

        writeSrtFile(tmpDir, "chunk_0001.srt", listOf(
            Triple(280.0, 300.0, "overlap2"),
            Triple(500.0, 510.0, "last"),
        ))
        tmpDir.resolve("chunk_0001.done").writeText("{}")
        // 不写 chunk_0001.meta.json

        callTryMergeChunks(tmpDir, 2, "large-v3", "zh")

        val merged = tmpDir.resolve("transcript.srt").readText()
        // 无 core region → 全部保留，共 4 segments
        assertTrue(merged.contains("first"))
        assertTrue(merged.contains("overlap"))
        assertTrue(merged.contains("overlap2"))
        assertTrue(merged.contains("last"))

        val seqNumbers = merged.lines().filter { it.trim().matches(Regex("^\\d+$")) }
        assertEquals(listOf("1", "2", "3", "4"), seqNumbers)
    }

    // ── canSkip 辅助 ────────────────────────────────────────────────────────

    private fun makeTask(
        audioPath: String,
        outputDir: String,
        chunkIndex: Int = 0,
        modelSize: String = "large-v3",
        language: String? = "zh",
    ): Task = Task(
        id = "test-task-${System.nanoTime()}",
        type = "TRANSCRIBE",
        workflowRunId = "wf-test",
        materialId = "mat-test",
        priority = TaskPriority.DEV_TEST_DEFAULT,
        createdAt = System.currentTimeMillis() / 1000,
        payload = buildJsonObject {
            put("audio_path", audioPath)
            put("output_dir", outputDir)
            put("chunk_index", chunkIndex)
            put("model_size", modelSize)
            put("language", language)
        }.toString(),
    )

    private fun writeDoneFile(dir: File, prefix: String, inputHash: String, modelSize: String, language: String?) {
        dir.resolve("$prefix.done").writeText(
            buildJsonObject {
                put("input_hash", inputHash)
                put("output_hash", "fakehash")
                put("model_size", modelSize)
                put("language", language)
            }.toString()
        )
    }

    // ── F15 ──────────────────────────────────────────────────────────────────

    @Test
    fun `F15 - canSkip returns true when cache matches`() = runBlocking {
        val audioFile = tmpDir.resolve("audio.m4a").also { it.writeText("audio content") }
        val audioHash = callSha256String("audio content")

        writeDoneFile(tmpDir, "chunk_0000", audioHash, "large-v3", "zh")

        val task = makeTask(
            audioPath = audioFile.absolutePath,
            outputDir = tmpDir.absolutePath,
            modelSize = "large-v3",
            language = "zh",
        )
        assertTrue(TranscribeExecutor.canSkip(task), "缓存命中时 canSkip 应返回 true")
    }

    // ── F16 ──────────────────────────────────────────────────────────────────

    @Test
    fun `F16 - canSkip returns false when hash mismatches`() = runBlocking {
        val audioFile = tmpDir.resolve("audio.m4a").also { it.writeText("new content") }
        val oldHash = callSha256String("old content") // 不同于实际文件内容

        writeDoneFile(tmpDir, "chunk_0000", oldHash, "large-v3", "zh")

        val task = makeTask(
            audioPath = audioFile.absolutePath,
            outputDir = tmpDir.absolutePath,
            modelSize = "large-v3",
            language = "zh",
        )
        assertFalse(TranscribeExecutor.canSkip(task), "hash 不匹配时 canSkip 应返回 false")
    }

    // ── F17 ──────────────────────────────────────────────────────────────────

    @Test
    fun `F17 - canSkip returns false when model_size mismatches`() = runBlocking {
        val audioFile = tmpDir.resolve("audio.m4a").also { it.writeText("audio content") }
        val audioHash = callSha256String("audio content")

        // done 文件记录 model_size=medium，但 payload 要求 large-v3
        writeDoneFile(tmpDir, "chunk_0000", audioHash, "medium", "zh")

        val task = makeTask(
            audioPath = audioFile.absolutePath,
            outputDir = tmpDir.absolutePath,
            modelSize = "large-v3",
            language = "zh",
        )
        assertFalse(TranscribeExecutor.canSkip(task), "model_size 不匹配时 canSkip 应返回 false")
    }

    // ── F18 ──────────────────────────────────────────────────────────────────

    @Test
    fun `F18 - canSkip returns false when language mismatches`() = runBlocking {
        val audioFile = tmpDir.resolve("audio.m4a").also { it.writeText("audio content") }
        val audioHash = callSha256String("audio content")

        // done 文件记录 language=en，但 payload 要求 zh
        writeDoneFile(tmpDir, "chunk_0000", audioHash, "large-v3", "en")

        val task = makeTask(
            audioPath = audioFile.absolutePath,
            outputDir = tmpDir.absolutePath,
            modelSize = "large-v3",
            language = "zh",
        )
        assertFalse(TranscribeExecutor.canSkip(task), "language 不匹配时 canSkip 应返回 false")
    }

    // ── F19 ──────────────────────────────────────────────────────────────────

    @Test
    fun `F19 - canSkip returns false when done file does not exist`() = runBlocking {
        val audioFile = tmpDir.resolve("audio.m4a").also { it.writeText("audio content") }
        // 不写 done 文件

        val task = makeTask(
            audioPath = audioFile.absolutePath,
            outputDir = tmpDir.absolutePath,
        )
        assertFalse(TranscribeExecutor.canSkip(task), "done 文件不存在时 canSkip 应返回 false")
    }

    // ── F20 ──────────────────────────────────────────────────────────────────

    @Test
    fun `F20 - canSkip returns false when audio file does not exist`() = runBlocking {
        val audioHash = callSha256String("audio content")
        writeDoneFile(tmpDir, "chunk_0000", audioHash, "large-v3", "zh")

        // 音频文件路径指向不存在的文件
        val task = makeTask(
            audioPath = tmpDir.resolve("nonexistent.m4a").absolutePath,
            outputDir = tmpDir.absolutePath,
        )
        assertFalse(TranscribeExecutor.canSkip(task), "音频文件不存在时 canSkip 应返回 false")
    }
}
