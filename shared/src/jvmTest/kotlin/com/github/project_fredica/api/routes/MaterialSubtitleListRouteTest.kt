package com.github.project_fredica.api.routes

// =============================================================================
// MaterialSubtitleListRouteTest
// =============================================================================
//
// 被测对象：MaterialSubtitleListRoute.handler()
//
// 测试矩阵：
//   L1. 素材不存在 → 返回空列表
//   L2. 非 bilibili 素材，无 ASR 结果 → 返回空列表
//   L3. 非 bilibili 素材，有 transcript.srt → 返回 1 条 source=asr 条目
//   L4. bilibili 素材，无 ASR 结果，无 bilibili 缓存 → 返回空列表
//   L5. bilibili 素材，有 transcript.srt → 包含 source=asr 条目
//   L6. ASR 条目字段校验：lan=asr, lan_doc=ASR 识别, type=1, subtitle_url=绝对路径
//   L7. transcript.srt 为空文件时不返回 ASR 条目
// =============================================================================

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.db.MaterialCategoryDb
import com.github.project_fredica.db.MaterialDb
import com.github.project_fredica.db.MaterialService
import com.github.project_fredica.db.MaterialVideo
import com.github.project_fredica.db.MaterialVideoDb
import com.github.project_fredica.db.MaterialVideoService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.ktorm.database.Database
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaterialSubtitleListRouteTest {

    private lateinit var db: Database
    private lateinit var tmpDbFile: File
    private val matId = "test-subtitle-list-${System.nanoTime()}"
    private lateinit var mediaDir: File

    @BeforeTest
    fun setup() = runBlocking {
        tmpDbFile = File.createTempFile("subtitle_list_test_", ".db").also { it.deleteOnExit() }
        db = Database.connect(
            url = "jdbc:sqlite:${tmpDbFile.absolutePath}",
            driver = "org.sqlite.JDBC",
        )
        val materialDb = MaterialDb(db)
        val materialVideoDb = MaterialVideoDb(db)
        materialDb.initialize()
        materialVideoDb.initialize()
        MaterialCategoryDb(db).initialize()
        MaterialService.initialize(materialDb)
        MaterialVideoService.initialize(materialVideoDb)

        mediaDir = AppUtil.Paths.materialMediaDir(matId)
    }

    @AfterTest
    fun tearDown() {
        mediaDir.deleteRecursively()
    }

    private fun queryParam(id: String) = """{"material_id":["$id"]}"""

    private suspend fun insertMaterial(id: String = matId, sourceType: String = "local") {
        MaterialVideoService.repo.upsertAll(listOf(
            MaterialVideo(
                id = id, title = "测试", sourceType = sourceType, sourceId = "src",
                coverUrl = "", description = "", duration = 60,
                localVideoPath = "", localAudioPath = "", transcriptPath = "",
                extra = "{}", createdAt = 0L, updatedAt = 0L,
            )
        ))
    }

    private fun writeSrt(content: String = "1\n00:00:00,000 --> 00:00:01,000\n你好\n\n") {
        val asrDir = mediaDir.resolve("asr_result").also { it.mkdirs() }
        asrDir.resolve("transcript.srt").writeText(content)
    }

    private fun parseArray(result: ValidJsonString): JsonArray =
        Json.parseToJsonElement(result.str).jsonArray

    // ── L1 ───────────────────────────────────────────────────────────────────

    @Test
    fun `L1 - material not found returns empty list`() = runBlocking {
        val result = MaterialSubtitleListRoute.handler(queryParam("no-such-id"))
        assertEquals(0, parseArray(result).size)
    }

    // ── L2 ───────────────────────────────────────────────────────────────────

    @Test
    fun `L2 - non-bilibili material with no ASR result returns empty list`() = runBlocking {
        insertMaterial(sourceType = "local")
        val result = MaterialSubtitleListRoute.handler(queryParam(matId))
        assertEquals(0, parseArray(result).size)
    }

    // ── L3 ───────────────────────────────────────────────────────────────────

    @Test
    fun `L3 - non-bilibili material with transcript srt returns asr item`() = runBlocking {
        insertMaterial(sourceType = "local")
        writeSrt()

        val arr = parseArray(MaterialSubtitleListRoute.handler(queryParam(matId)))
        assertEquals(1, arr.size, "应有 1 条 ASR 字幕")
        assertEquals("asr", arr[0].jsonObject["source"]?.jsonPrimitive?.content)
    }

    // ── L4 ───────────────────────────────────────────────────────────────────

    @Test
    fun `L4 - bilibili material with no cache and no ASR returns empty list`() = runBlocking {
        insertMaterial(sourceType = "bilibili")
        // 不写 transcript.srt，不插入 bilibili 缓存
        val result = MaterialSubtitleListRoute.handler(queryParam(matId))
        assertEquals(0, parseArray(result).size)
    }

    // ── L5 ───────────────────────────────────────────────────────────────────

    @Test
    fun `L5 - bilibili material with transcript srt includes asr item`() = runBlocking {
        insertMaterial(sourceType = "bilibili")
        writeSrt()

        val arr = parseArray(MaterialSubtitleListRoute.handler(queryParam(matId)))
        assertTrue(arr.any { it.jsonObject["source"]?.jsonPrimitive?.content == "asr" },
            "应包含 source=asr 条目")
    }

    // ── L6 ───────────────────────────────────────────────────────────────────

    @Test
    fun `L6 - asr item has correct fields`() = runBlocking {
        insertMaterial(sourceType = "local")
        writeSrt()

        val arr = parseArray(MaterialSubtitleListRoute.handler(queryParam(matId)))
        assertEquals(1, arr.size)
        val item = arr[0].jsonObject

        assertEquals("asr",       item["lan"]?.jsonPrimitive?.content)
        assertEquals("ASR 识别",  item["lan_doc"]?.jsonPrimitive?.content)
        assertEquals("asr",       item["source"]?.jsonPrimitive?.content)
        assertEquals(1,            item["type"]?.jsonPrimitive?.int)

        val subtitleUrl = item["subtitle_url"]?.jsonPrimitive?.content ?: ""
        assertTrue(subtitleUrl.endsWith("transcript.srt"), "subtitle_url 应指向 transcript.srt，实际=$subtitleUrl")
        assertTrue(File(subtitleUrl).isAbsolute, "subtitle_url 应为绝对路径")
    }

    // ── L7 ───────────────────────────────────────────────────────────────────

    @Test
    fun `L7 - empty transcript srt is not returned`() = runBlocking {
        insertMaterial(sourceType = "local")
        // 写空文件
        val asrDir = mediaDir.resolve("asr_result").also { it.mkdirs() }
        asrDir.resolve("transcript.srt").writeText("")

        val arr = parseArray(MaterialSubtitleListRoute.handler(queryParam(matId)))
        assertEquals(0, arr.size, "空 SRT 文件不应出现在列表中")
    }
}
