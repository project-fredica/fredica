package com.github.project_fredica.api.routes

// =============================================================================
// MaterialVideoStreamRouteTest
// =============================================================================
//
// 使用 Ktor TestApplication 对视频流路由进行集成测试。
//
// 测试覆盖：
//   1. Cookie 缺失         → 401 Unauthorized
//   2. material_id 缺失    → 400 Bad Request
//   3. 素材不存在           → 404 Not Found
//   4. 正常请求             → 200，Content-Type: video/mp4，含 ETag / Cache-Control
//   5. Range 请求           → 206 Partial Content，Content-Range 正确
//   6. If-None-Match 命中  → 304 Not Modified
// =============================================================================

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.auth.WebserverAuthTokenCache
import com.github.project_fredica.material_category.db.MaterialCategoryDb
import com.github.project_fredica.db.MaterialDb
import com.github.project_fredica.db.MaterialService
import com.github.project_fredica.db.MaterialVideo
import com.github.project_fredica.db.MaterialVideoDb
import com.github.project_fredica.db.MaterialVideoService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.partialcontent.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MaterialVideoStreamRouteTest {

    private lateinit var db: Database
    private lateinit var tmpDbFile: File
    private lateinit var testMediaDir: File
    private val testMaterialId = "test-stream-${System.nanoTime()}"

    @BeforeTest
    fun setup() = runBlocking {
        tmpDbFile = File.createTempFile("stream_test_", ".db").also { it.deleteOnExit() }
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
        WebserverAuthTokenCache.init("test-token")

        MaterialVideoService.repo.upsertAll(
            listOf(
                MaterialVideo(
                    id = testMaterialId,
                    title = "流测试视频",
                    sourceType = "bilibili",
                    sourceId = "BV_stream",
                    coverUrl = "",
                    description = "",
                    duration = 120,
                    localVideoPath = "",
                    localAudioPath = "",
                    transcriptPath = "",
                    extra = "{}",
                    createdAt = System.currentTimeMillis() / 1000,
                    updatedAt = System.currentTimeMillis() / 1000,
                )
            )
        )

        // 创建测试 MP4（1 KiB）
        testMediaDir = AppUtil.Paths.materialMediaDir(testMaterialId)
        testMediaDir.resolve("video.mp4").writeBytes(ByteArray(1024) { it.toByte() })
    }

    @AfterTest
    fun tearDown() {
        WebserverAuthTokenCache.invalidate()
        testMediaDir.deleteRecursively()
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(PartialContent)
            routing {
                get(MaterialVideoStreamRoute.PATH) {
                    MaterialVideoStreamRoute.handle(this)
                }
            }
        }
        block()
    }

    // ── 1. Cookie 缺失 → 401 ──────────────────────────────────────────────────

    @Test
    fun `no cookie returns 401`() = testApp {
        val resp = client.get(MaterialVideoStreamRoute.PATH) {
            parameter("material_id", testMaterialId)
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    // ── 2. material_id 缺失 → 400 ────────────────────────────────────────────

    @Test
    fun `missing material_id returns 400`() = testApp {
        val resp = client.get(MaterialVideoStreamRoute.PATH) {
            cookie("fredica_media_token", "test-token")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    // ── 3. 素材不存在 → 404 ──────────────────────────────────────────────────

    @Test
    fun `unknown material returns 404`() = testApp {
        val resp = client.get(MaterialVideoStreamRoute.PATH) {
            cookie("fredica_media_token", "test-token")
            parameter("material_id", "no-such-id")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    // ── 4. 正常请求 → 200 ────────────────────────────────────────────────────

    @Test
    fun `normal request returns 200 with mp4 content type`() = testApp {
        val resp = client.get(MaterialVideoStreamRoute.PATH) {
            cookie("fredica_media_token", "test-token")
            parameter("material_id", testMaterialId)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(ContentType.Video.MP4, resp.contentType()?.withoutParameters())
        assertNotNull(resp.headers[HttpHeaders.ETag], "ETag header must be present")
        assertEquals("no-cache", resp.headers[HttpHeaders.CacheControl])
        assertEquals(1024, resp.bodyAsBytes().size)
    }

    // ── 5. Range 请求 → 206 ──────────────────────────────────────────────────

    @Test
    fun `range request returns 206 partial content`() = testApp {
        val resp = client.get(MaterialVideoStreamRoute.PATH) {
            cookie("fredica_media_token", "test-token")
            parameter("material_id", testMaterialId)
            header(HttpHeaders.Range, "bytes=0-511")
        }
        assertEquals(HttpStatusCode.PartialContent, resp.status)
        val contentRange = resp.headers[HttpHeaders.ContentRange]
        assertNotNull(contentRange)
        assertTrue(
            contentRange.startsWith("bytes 0-511/1024"),
            "Expected 'bytes 0-511/1024', got '$contentRange'"
        )
        assertEquals(512, resp.bodyAsBytes().size)
    }

    // ── 6. If-None-Match 命中 → 304 ──────────────────────────────────────────

    @Test
    fun `matching etag returns 304 no body`() = testApp {
        // 先取 ETag
        val first = client.get(MaterialVideoStreamRoute.PATH) {
            cookie("fredica_media_token", "test-token")
            parameter("material_id", testMaterialId)
        }
        val etag = first.headers[HttpHeaders.ETag]
        assertNotNull(etag)

        // 带 If-None-Match 再次请求
        val second = client.get(MaterialVideoStreamRoute.PATH) {
            cookie("fredica_media_token", "test-token")
            parameter("material_id", testMaterialId)
            header(HttpHeaders.IfNoneMatch, etag)
        }
        assertEquals(HttpStatusCode.NotModified, second.status)
        assertEquals(0, second.bodyAsBytes().size)
    }
}
