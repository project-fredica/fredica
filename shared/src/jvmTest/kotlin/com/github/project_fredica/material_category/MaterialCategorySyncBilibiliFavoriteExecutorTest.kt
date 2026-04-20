package com.github.project_fredica.material_category

import com.github.project_fredica.apputil.bilibiliVideoId
import com.github.project_fredica.db.MaterialDb
import com.github.project_fredica.db.MaterialService
import com.github.project_fredica.db.MaterialVideoDb
import com.github.project_fredica.db.MaterialVideoService
import com.github.project_fredica.db.Task
import com.github.project_fredica.material_category.db.MaterialCategoryDb
import com.github.project_fredica.material_category.db.MaterialCategorySyncItemDb
import com.github.project_fredica.material_category.db.MaterialCategorySyncPlatformInfoDb
import com.github.project_fredica.material_category.model.MaterialCategorySyncPlatformInfo
import com.github.project_fredica.material_category.service.MaterialCategoryService
import com.github.project_fredica.material_category.service.MaterialCategorySyncItemService
import com.github.project_fredica.material_category.service.MaterialCategorySyncPlatformInfoService
import com.github.project_fredica.worker.executors.MaterialCategorySyncBilibiliFavoriteExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.ktorm.database.Database
import java.io.File
import kotlin.test.*

class MaterialCategorySyncBilibiliFavoriteExecutorTest {
    private lateinit var db: Database
    private lateinit var tmpFile: File
    private lateinit var categoryDb: MaterialCategoryDb
    private lateinit var syncPlatformInfoDb: MaterialCategorySyncPlatformInfoDb
    private lateinit var syncItemDb: MaterialCategorySyncItemDb

    private val now = System.currentTimeMillis() / 1000L
    private val categoryId = "cat-1"
    private val platformInfoId = "pi-1"

    private var savedApiClient: MaterialCategorySyncBilibiliFavoriteExecutor.ApiClient? = null

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_executor_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        runBlocking {
            val materialDb = MaterialDb(db)
            materialDb.initialize()
            MaterialService.initialize(materialDb)
            val materialVideoDb = MaterialVideoDb(db)
            materialVideoDb.initialize()
            MaterialVideoService.initialize(materialVideoDb)
            categoryDb = MaterialCategoryDb(db)
            categoryDb.initialize()
        }
        syncPlatformInfoDb = MaterialCategorySyncPlatformInfoDb(db)
        syncItemDb = MaterialCategorySyncItemDb(db)

        MaterialCategoryService.initialize(categoryDb)
        MaterialCategorySyncPlatformInfoService.initialize(syncPlatformInfoDb)
        MaterialCategorySyncItemService.initialize(syncItemDb)

        savedApiClient = MaterialCategorySyncBilibiliFavoriteExecutor.apiClient

        runBlocking {
            categoryDb.insertOrIgnore(
                com.github.project_fredica.material_category.model.MaterialCategory(
                    id = categoryId,
                    ownerId = "user-1",
                    name = "测试收藏夹",
                    description = "",
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
    }

    @AfterTest
    fun teardown() {
        savedApiClient?.let { MaterialCategorySyncBilibiliFavoriteExecutor.apiClient = it }
        tmpFile.delete()
    }

    private fun createPlatformInfo(
        id: String = platformInfoId,
        mediaId: Long = 12345L,
        syncCursor: String = "",
        syncState: String = "idle",
        displayName: String = "测试收藏夹",
    ) = runBlocking {
        val config = buildJsonObject { put("type", "bilibili_favorite"); put("media_id", mediaId) }.toString()
        syncPlatformInfoDb.create(
            MaterialCategorySyncPlatformInfo(
                id = id,
                syncType = "bilibili_favorite",
                platformId = "bilibili_fav_$mediaId",
                platformConfig = config,
                displayName = displayName,
                categoryId = categoryId,
                syncCursor = syncCursor,
                syncState = syncState,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    private fun makeTask(payload: String = buildJsonObject { put("platform_info_id", platformInfoId) }.toString()): Task {
        return Task(
            id = "task-1",
            type = "SYNC_BILIBILI_FAVORITE",
            workflowRunId = "wf-1",
            materialId = categoryId,
            status = "running",
            priority = 10,
            payload = payload,
            createdAt = now,
        )
    }

    private fun buildMediaItem(bvid: String, favTime: Long): JsonObject = buildJsonObject {
        put("bvid", bvid)
        put("title", "视频 $bvid")
        put("cover", "https://example.com/$bvid.jpg")
        put("intro", "简介 $bvid")
        put("pubtime", favTime - 100)
        put("duration", 300)
        put("fav_time", favTime)
        put("upper", buildJsonObject {
            put("mid", 123456)
            put("name", "UP主")
            put("face", "https://example.com/face.jpg")
        })
        put("cnt_info", buildJsonObject {
            put("play", 1000)
            put("collect", 50)
            put("danmaku", 200)
        })
    }

    private fun buildPageResponse(medias: List<JsonObject>, hasMore: Boolean): JsonObject = buildJsonObject {
        put("fid", "12345")
        put("page", 1)
        put("medias", buildJsonArray { medias.forEach { add(it) } })
        put("has_more", hasMore.toString())
    }

    @Test
    fun ex1_full_sync_no_cursor() = runBlocking {
        createPlatformInfo()

        val page1Medias = listOf(
            buildMediaItem("BV1aaa", 1000L),
            buildMediaItem("BV1bbb", 900L),
            buildMediaItem("BV1ccc", 800L),
        )
        val page2Medias = listOf(
            buildMediaItem("BV1ddd", 700L),
            buildMediaItem("BV1eee", 600L),
        )

        MaterialCategorySyncBilibiliFavoriteExecutor.apiClient = object : MaterialCategorySyncBilibiliFavoriteExecutor.ApiClient {
            override suspend fun getInfo(fid: String): JsonObject = buildJsonObject { put("title", "测试收藏夹") }
            override suspend fun getPage(fid: String, page: Int): JsonObject {
                assertEquals("12345", fid)
                return when (page) {
                    1 -> buildPageResponse(page1Medias, hasMore = true)
                    2 -> buildPageResponse(page2Medias, hasMore = false)
                    else -> error("Unexpected page: $page")
                }
            }
        }

        val result = MaterialCategorySyncBilibiliFavoriteExecutor.execute(makeTask())
        assertTrue(result.isSuccess, "Expected success but got: ${result.error}")

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("idle", info.syncState)
        assertEquals("1000", info.syncCursor)
        assertEquals(5, info.itemCount)
        assertEquals(0, info.failCount)
        assertNull(info.lastError)
        assertNotNull(info.lastSyncedAt)

        assertEquals(5, syncItemDb.countByPlatformInfo(platformInfoId))
        Unit
    }

    @Test
    fun ex2_incremental_sync_with_cursor() = runBlocking {
        createPlatformInfo(syncCursor = "800")

        val medias = listOf(
            buildMediaItem("BV1new1", 1000L),
            buildMediaItem("BV1new2", 900L),
            buildMediaItem("BV1old", 800L),
        )

        MaterialCategorySyncBilibiliFavoriteExecutor.apiClient = object : MaterialCategorySyncBilibiliFavoriteExecutor.ApiClient {
            override suspend fun getInfo(fid: String): JsonObject = buildJsonObject { put("title", "测试收藏夹") }
            override suspend fun getPage(fid: String, page: Int): JsonObject {
                assertEquals(1, page)
                return buildPageResponse(medias, hasMore = true)
            }
        }

        val result = MaterialCategorySyncBilibiliFavoriteExecutor.execute(makeTask())
        assertTrue(result.isSuccess)

        assertEquals(2, syncItemDb.countByPlatformInfo(platformInfoId))

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("idle", info.syncState)
        assertEquals("1000", info.syncCursor)
        assertEquals(2, info.itemCount)
        Unit
    }

    @Test
    fun ex3_sync_success_resets_state() = runBlocking {
        createPlatformInfo(syncState = "idle")

        MaterialCategorySyncBilibiliFavoriteExecutor.apiClient = object : MaterialCategorySyncBilibiliFavoriteExecutor.ApiClient {
            override suspend fun getInfo(fid: String): JsonObject = buildJsonObject { put("title", "测试收藏夹") }
            override suspend fun getPage(fid: String, page: Int): JsonObject {
                return buildPageResponse(
                    listOf(buildMediaItem("BV1xxx", 500L)),
                    hasMore = false,
                )
            }
        }

        val result = MaterialCategorySyncBilibiliFavoriteExecutor.execute(makeTask())
        assertTrue(result.isSuccess)

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("idle", info.syncState)
        assertEquals(0, info.failCount)
        assertNull(info.lastError)
        Unit
    }

    @Test
    fun ex4_api_failure_sets_failed_state() = runBlocking {
        createPlatformInfo()

        MaterialCategorySyncBilibiliFavoriteExecutor.apiClient = object : MaterialCategorySyncBilibiliFavoriteExecutor.ApiClient {
            override suspend fun getInfo(fid: String): JsonObject = buildJsonObject { put("title", "测试收藏夹") }
            override suspend fun getPage(fid: String, page: Int): JsonObject {
                throw RuntimeException("Network timeout")
            }
        }

        val result = MaterialCategorySyncBilibiliFavoriteExecutor.execute(makeTask())
        assertFalse(result.isSuccess)
        assertEquals("SYNC_ERROR", result.errorType)
        assertTrue(result.error!!.contains("Network timeout"))

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("failed", info.syncState)
        assertEquals(1, info.failCount)
        assertEquals("Network timeout", info.lastError)
        Unit
    }

    @Test
    fun ex5_link_materials_called_with_sync_added_by() = runBlocking {
        createPlatformInfo()

        MaterialCategorySyncBilibiliFavoriteExecutor.apiClient = object : MaterialCategorySyncBilibiliFavoriteExecutor.ApiClient {
            override suspend fun getInfo(fid: String): JsonObject = buildJsonObject { put("title", "测试收藏夹") }
            override suspend fun getPage(fid: String, page: Int): JsonObject {
                return buildPageResponse(
                    listOf(buildMediaItem("BV1link", 500L)),
                    hasMore = false,
                )
            }
        }

        val result = MaterialCategorySyncBilibiliFavoriteExecutor.execute(makeTask())
        assertTrue(result.isSuccess)

        val expectedMaterialId = bilibiliVideoId("BV1link")
        var addedBy: String? = null
        db.useConnection { conn ->
            conn.prepareStatement(
                "SELECT added_by FROM material_category_rel WHERE material_id = ? AND category_id = ?"
            ).use { ps ->
                ps.setString(1, expectedMaterialId)
                ps.setString(2, categoryId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) addedBy = rs.getString("added_by")
                }
            }
        }
        assertEquals("sync", addedBy)
        Unit
    }

    @Test
    fun ex6_empty_favorite_succeeds() = runBlocking {
        createPlatformInfo()

        MaterialCategorySyncBilibiliFavoriteExecutor.apiClient = object : MaterialCategorySyncBilibiliFavoriteExecutor.ApiClient {
            override suspend fun getInfo(fid: String): JsonObject = buildJsonObject { put("title", "测试收藏夹") }
            override suspend fun getPage(fid: String, page: Int): JsonObject {
                return buildPageResponse(emptyList(), hasMore = false)
            }
        }

        val result = MaterialCategorySyncBilibiliFavoriteExecutor.execute(makeTask())
        assertTrue(result.isSuccess)

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("idle", info.syncState)
        assertEquals(0, info.itemCount)
        Unit
    }

    @Test
    fun ex7_invalid_payload_returns_error() = runBlocking {
        val result = MaterialCategorySyncBilibiliFavoriteExecutor.execute(
            makeTask(payload = "not json")
        )
        assertFalse(result.isSuccess)
        assertEquals("PAYLOAD_ERROR", result.errorType)
        Unit
    }

    @Test
    fun ex8_sync_creates_material_records() = runBlocking {
        createPlatformInfo()

        MaterialCategorySyncBilibiliFavoriteExecutor.apiClient = object : MaterialCategorySyncBilibiliFavoriteExecutor.ApiClient {
            override suspend fun getInfo(fid: String): JsonObject = buildJsonObject { put("title", "测试收藏夹") }
            override suspend fun getPage(fid: String, page: Int): JsonObject {
                return buildPageResponse(
                    listOf(buildMediaItem("BV1mat", 500L)),
                    hasMore = false,
                )
            }
        }

        val result = MaterialCategorySyncBilibiliFavoriteExecutor.execute(makeTask())
        assertTrue(result.isSuccess)

        val expectedId = bilibiliVideoId("BV1mat")
        assertEquals("bilibili_bvid__BV1mat__P1", expectedId)

        val video = MaterialVideoService.repo.findById(expectedId)
        assertNotNull(video)
        assertEquals("视频 BV1mat", video.title)
        assertEquals("https://example.com/BV1mat.jpg", video.coverUrl)
        assertEquals(300, video.duration)
        assertEquals("bilibili_favorite", video.sourceType)
        assertEquals("BV1mat", video.sourceId)
        Unit
    }

    @Test
    fun ex9_repeat_sync_idempotent_material_records() = runBlocking {
        createPlatformInfo()

        val mockClient = object : MaterialCategorySyncBilibiliFavoriteExecutor.ApiClient {
            override suspend fun getInfo(fid: String): JsonObject = buildJsonObject { put("title", "测试收藏夹") }
            override suspend fun getPage(fid: String, page: Int): JsonObject {
                return buildPageResponse(
                    listOf(buildMediaItem("BV1dup", 500L)),
                    hasMore = false,
                )
            }
        }
        MaterialCategorySyncBilibiliFavoriteExecutor.apiClient = mockClient

        val r1 = MaterialCategorySyncBilibiliFavoriteExecutor.execute(makeTask())
        assertTrue(r1.isSuccess)

        val r2 = MaterialCategorySyncBilibiliFavoriteExecutor.execute(makeTask())
        assertTrue(r2.isSuccess)

        assertNotNull(MaterialVideoService.repo.findById(bilibiliVideoId("BV1dup")))
        Unit
    }

    @Test
    fun ex10_missing_platform_info_returns_not_found() = runBlocking {
        val result = MaterialCategorySyncBilibiliFavoriteExecutor.execute(
            makeTask(payload = """{"platform_info_id":"nonexistent"}""")
        )
        assertFalse(result.isSuccess)
        assertEquals("NOT_FOUND", result.errorType)
        Unit
    }

    @Test
    fun ex11_material_id_format_correct() = runBlocking {
        createPlatformInfo()

        MaterialCategorySyncBilibiliFavoriteExecutor.apiClient = object : MaterialCategorySyncBilibiliFavoriteExecutor.ApiClient {
            override suspend fun getInfo(fid: String): JsonObject = buildJsonObject { put("title", "测试收藏夹") }
            override suspend fun getPage(fid: String, page: Int): JsonObject {
                return buildPageResponse(
                    listOf(buildMediaItem("BV1fmt", 500L)),
                    hasMore = false,
                )
            }
        }

        val result = MaterialCategorySyncBilibiliFavoriteExecutor.execute(makeTask())
        assertTrue(result.isSuccess)

        val items = syncItemDb.listByPlatformInfo(platformInfoId)
        assertEquals(1, items.size)
        assertEquals("bilibili_bvid__BV1fmt__P1", items[0].materialId)
        Unit
    }

    @Test
    fun ex12_display_name_fetched_when_empty() = runBlocking {
        createPlatformInfo(displayName = "")

        MaterialCategorySyncBilibiliFavoriteExecutor.apiClient = object : MaterialCategorySyncBilibiliFavoriteExecutor.ApiClient {
            override suspend fun getInfo(fid: String): JsonObject = buildJsonObject {
                put("title", "我的音乐收藏")
            }
            override suspend fun getPage(fid: String, page: Int): JsonObject {
                return buildPageResponse(
                    listOf(buildMediaItem("BV1dn", 500L)),
                    hasMore = false,
                )
            }
        }

        val result = MaterialCategorySyncBilibiliFavoriteExecutor.execute(makeTask())
        assertTrue(result.isSuccess)

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("我的音乐收藏", info.displayName)
        Unit
    }

    @Test
    fun ex13_display_name_updated_when_already_set() = runBlocking {
        createPlatformInfo(displayName = "旧收藏夹名")

        MaterialCategorySyncBilibiliFavoriteExecutor.apiClient = object : MaterialCategorySyncBilibiliFavoriteExecutor.ApiClient {
            override suspend fun getInfo(fid: String): JsonObject = buildJsonObject {
                put("title", "新收藏夹名")
            }
            override suspend fun getPage(fid: String, page: Int): JsonObject {
                return buildPageResponse(
                    listOf(buildMediaItem("BV1upd", 500L)),
                    hasMore = false,
                )
            }
        }

        val result = MaterialCategorySyncBilibiliFavoriteExecutor.execute(makeTask())
        assertTrue(result.isSuccess)

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("新收藏夹名", info.displayName)
        Unit
    }

    @Test
    fun ex14_display_name_fetch_failure_does_not_break_sync() = runBlocking {
        createPlatformInfo(displayName = "")

        MaterialCategorySyncBilibiliFavoriteExecutor.apiClient = object : MaterialCategorySyncBilibiliFavoriteExecutor.ApiClient {
            override suspend fun getInfo(fid: String): JsonObject {
                throw RuntimeException("API unavailable")
            }
            override suspend fun getPage(fid: String, page: Int): JsonObject {
                return buildPageResponse(
                    listOf(buildMediaItem("BV1dn2", 500L)),
                    hasMore = false,
                )
            }
        }

        val result = MaterialCategorySyncBilibiliFavoriteExecutor.execute(makeTask())
        assertTrue(result.isSuccess)

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("", info.displayName)
        assertEquals(1, info.itemCount)
        Unit
    }
}
