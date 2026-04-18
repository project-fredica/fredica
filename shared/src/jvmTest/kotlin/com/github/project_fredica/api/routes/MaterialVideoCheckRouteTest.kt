package com.github.project_fredica.api.routes

// =============================================================================
// MaterialVideoCheckRouteTest
// =============================================================================
//
// 直接调用 route handler() 验证响应 JSON，无需 HTTP 服务器。
//
// 测试覆盖：
//   1. video.mp4 + transcode.done 均存在   → ready:true，含 file_size/file_mtime
//   2. video.mp4 存在，transcode.done 缺失 → ready:false（防止返回转码中文件）
//   3. 两者均不存在                        → ready:false
//   4. 缺少 material_id 参数              → error:MISSING_MATERIAL_ID
//   5. material_id 指向不存在的素材        → error:MATERIAL_NOT_FOUND
// =============================================================================

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.material_category.db.MaterialCategoryDb
import com.github.project_fredica.db.MaterialDb
import com.github.project_fredica.db.MaterialService
import com.github.project_fredica.db.MaterialVideo
import com.github.project_fredica.db.MaterialVideoDb
import com.github.project_fredica.db.MaterialVideoService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.ktorm.database.Database
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MaterialVideoCheckRouteTest {

    private lateinit var db: Database
    private lateinit var tmpDbFile: File
    private lateinit var testMediaDir: File
    private val testMaterialId = "test-video-check-${System.nanoTime()}"

    @BeforeTest
    fun setup() = runBlocking {
        tmpDbFile = File.createTempFile("video_check_test_", ".db").also { it.deleteOnExit() }
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

        // 插入测试素材（upsertAll 写 material + material_video 两张表）
        MaterialVideoService.repo.upsertAll(
            listOf(
                MaterialVideo(
                    id = testMaterialId,
                    title = "测试视频",
                    sourceType = "bilibili",
                    sourceId = "BV_test",
                    coverUrl = "",
                    description = "",
                    duration = 60,
                    localVideoPath = "",
                    localAudioPath = "",
                    transcriptPath = "",
                    extra = "{}",
                    createdAt = System.currentTimeMillis() / 1000,
                    updatedAt = System.currentTimeMillis() / 1000,
                )
            )
        )

        // 获取 AppUtil.Paths 对应的媒体目录（lazy 初始化后固定为 .data/media/）
        testMediaDir = AppUtil.Paths.materialMediaDir(testMaterialId)
    }

    @AfterTest
    fun tearDown() {
        testMediaDir.deleteRecursively()
    }

    private val noContext = RouteContext(identity = null, clientIp = null, userAgent = null)

    private fun queryJson(materialId: String?): String {
        if (materialId == null) return "{}"
        return """{"material_id":["$materialId"]}"""
    }

    @Test
    fun `ready - both mp4 and done exist`() = runBlocking {
        testMediaDir.resolve("video.mp4").writeBytes(ByteArray(2048))
        testMediaDir.resolve("transcode.done").createNewFile()

        val result = MaterialVideoCheckRoute.handler(queryJson(testMaterialId), noContext)
        val json = Json.parseToJsonElement(result.str).jsonObject

        assertEquals(true, json["ready"]?.jsonPrimitive?.boolean)
        val mtime = json["file_mtime"]?.jsonPrimitive?.long
        assertNotNull(mtime)
        assertTrue(mtime > 0)
        assertEquals(2048L, json["file_size"]?.jsonPrimitive?.long)
    }

    @Test
    fun `not ready - mp4 exists but done missing`() = runBlocking {
        testMediaDir.resolve("video.mp4").writeBytes(ByteArray(512))
        // transcode.done 不创建

        val result = MaterialVideoCheckRoute.handler(queryJson(testMaterialId), noContext)
        val json = Json.parseToJsonElement(result.str).jsonObject

        assertEquals(false, json["ready"]?.jsonPrimitive?.boolean)
        // file_mtime / file_size 应为 null
        assertEquals("null", json["file_mtime"].toString())
        assertEquals("null", json["file_size"].toString())
    }

    @Test
    fun `not ready - neither file exists`() = runBlocking {
        val result = MaterialVideoCheckRoute.handler(queryJson(testMaterialId), noContext)
        val json = Json.parseToJsonElement(result.str).jsonObject

        assertEquals(false, json["ready"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `error - missing material_id`() = runBlocking {
        val result = MaterialVideoCheckRoute.handler(queryJson(null), noContext)
        val json = Json.parseToJsonElement(result.str).jsonObject

        assertEquals("MISSING_MATERIAL_ID", json["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `error - material not found`() = runBlocking {
        val result = MaterialVideoCheckRoute.handler(queryJson("no-such-id"), noContext)
        val json = Json.parseToJsonElement(result.str).jsonObject

        assertEquals("MATERIAL_NOT_FOUND", json["error"]?.jsonPrimitive?.content)
    }
}
