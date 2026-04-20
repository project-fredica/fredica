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
import com.github.project_fredica.worker.executors.MaterialCategorySyncBilibiliVideoPagesExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.ktorm.database.Database
import java.io.File
import kotlin.test.*

class MaterialCategorySyncBilibiliVideoPagesExecutorTest {
    private lateinit var db: Database
    private lateinit var tmpFile: File
    private lateinit var categoryDb: MaterialCategoryDb
    private lateinit var syncPlatformInfoDb: MaterialCategorySyncPlatformInfoDb
    private lateinit var syncItemDb: MaterialCategorySyncItemDb

    private val now = System.currentTimeMillis() / 1000L
    private val categoryId = "cat-1"
    private val platformInfoId = "pi-1"
    private val testBvid = "BV1test123"

    private var savedApiClient: MaterialCategorySyncBilibiliVideoPagesExecutor.ApiClient? = null

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

        savedApiClient = MaterialCategorySyncBilibiliVideoPagesExecutor.apiClient

        runBlocking {
            categoryDb.insertOrIgnore(
                com.github.project_fredica.material_category.model.MaterialCategory(
                    id = categoryId,
                    ownerId = "user-1",
                    name = "测试多P视频",
                    description = "",
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
    }

    @AfterTest
    fun teardown() {
        savedApiClient?.let { MaterialCategorySyncBilibiliVideoPagesExecutor.apiClient = it }
        tmpFile.delete()
    }

    private fun createPlatformInfo(
        id: String = platformInfoId,
        bvid: String = testBvid,
        syncState: String = "idle",
        displayName: String = "测试多P视频",
    ) = runBlocking {
        val config = buildJsonObject { put("type", "bilibili_video_pages"); put("bvid", bvid) }.toString()
        syncPlatformInfoDb.create(
            MaterialCategorySyncPlatformInfo(
                id = id,
                syncType = "bilibili_video_pages",
                platformId = bvid,
                platformConfig = config,
                displayName = displayName,
                categoryId = categoryId,
                syncCursor = "",
                syncState = syncState,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    private fun makeTask(payload: String = buildJsonObject { put("platform_info_id", platformInfoId) }.toString()): Task {
        return Task(
            id = "task-1",
            type = "SYNC_BILIBILI_VIDEO_PAGES",
            workflowRunId = "wf-1",
            materialId = categoryId,
            status = "running",
            priority = 10,
            payload = payload,
            createdAt = now,
        )
    }

    private fun buildPagesResponse(pageCount: Int): JsonArray = buildJsonArray {
        for (i in 1..pageCount) {
            add(buildJsonObject {
                put("page", i)
                put("part", "P$i 标题")
                put("duration", 300)
            })
        }
    }

    @Test
    fun ev1_full_sync_multi_pages() = runBlocking {
        createPlatformInfo()

        MaterialCategorySyncBilibiliVideoPagesExecutor.apiClient = object : MaterialCategorySyncBilibiliVideoPagesExecutor.ApiClient {
            override suspend fun getInfo(bvid: String): JsonObject = buildJsonObject { put("title", "测试多P视频") }
            override suspend fun getPages(bvid: String): JsonArray {
                assertEquals(testBvid, bvid)
                return buildPagesResponse(4)
            }
        }

        val result = MaterialCategorySyncBilibiliVideoPagesExecutor.execute(makeTask())
        assertTrue(result.isSuccess, "Expected success but got: ${result.error}")

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("idle", info.syncState)
        assertEquals(4, info.itemCount)
        assertEquals(0, info.failCount)
        assertNull(info.lastError)
        assertNotNull(info.lastSyncedAt)

        assertEquals(4, syncItemDb.countByPlatformInfo(platformInfoId))

        val items = syncItemDb.listByPlatformInfo(platformInfoId)
        val materialIds = items.map { it.materialId }.toSet()
        for (i in 1..4) {
            assertTrue(materialIds.contains("bilibili_bvid__${testBvid}__P$i"), "Missing P$i")
        }
        Unit
    }

    @Test
    fun ev2_repeat_sync_idempotent() = runBlocking {
        createPlatformInfo()

        val mockClient = object : MaterialCategorySyncBilibiliVideoPagesExecutor.ApiClient {
            override suspend fun getInfo(bvid: String): JsonObject = buildJsonObject { put("title", "测试多P视频") }
            override suspend fun getPages(bvid: String): JsonArray {
                return buildPagesResponse(3)
            }
        }
        MaterialCategorySyncBilibiliVideoPagesExecutor.apiClient = mockClient

        val result1 = MaterialCategorySyncBilibiliVideoPagesExecutor.execute(makeTask())
        assertTrue(result1.isSuccess)
        assertEquals(3, syncItemDb.countByPlatformInfo(platformInfoId))

        val result2 = MaterialCategorySyncBilibiliVideoPagesExecutor.execute(makeTask())
        assertTrue(result2.isSuccess)
        assertEquals(3, syncItemDb.countByPlatformInfo(platformInfoId))
        Unit
    }

    @Test
    fun ev3_sync_success_resets_state() = runBlocking {
        createPlatformInfo(syncState = "idle")

        MaterialCategorySyncBilibiliVideoPagesExecutor.apiClient = object : MaterialCategorySyncBilibiliVideoPagesExecutor.ApiClient {
            override suspend fun getInfo(bvid: String): JsonObject = buildJsonObject { put("title", "测试多P视频") }
            override suspend fun getPages(bvid: String): JsonArray {
                return buildPagesResponse(1)
            }
        }

        val result = MaterialCategorySyncBilibiliVideoPagesExecutor.execute(makeTask())
        assertTrue(result.isSuccess)

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("idle", info.syncState)
        assertEquals(0, info.failCount)
        assertNull(info.lastError)
        Unit
    }

    @Test
    fun ev4_api_failure_sets_failed_state() = runBlocking {
        createPlatformInfo()

        MaterialCategorySyncBilibiliVideoPagesExecutor.apiClient = object : MaterialCategorySyncBilibiliVideoPagesExecutor.ApiClient {
            override suspend fun getInfo(bvid: String): JsonObject = buildJsonObject { put("title", "测试多P视频") }
            override suspend fun getPages(bvid: String): JsonArray {
                throw RuntimeException("Video not found")
            }
        }

        val result = MaterialCategorySyncBilibiliVideoPagesExecutor.execute(makeTask())
        assertFalse(result.isSuccess)
        assertEquals("SYNC_ERROR", result.errorType)
        assertTrue(result.error!!.contains("Video not found"))

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("failed", info.syncState)
        assertEquals(1, info.failCount)
        assertEquals("Video not found", info.lastError)
        Unit
    }

    @Test
    fun ev5_sync_creates_material_records() = runBlocking {
        createPlatformInfo()

        MaterialCategorySyncBilibiliVideoPagesExecutor.apiClient = object : MaterialCategorySyncBilibiliVideoPagesExecutor.ApiClient {
            override suspend fun getInfo(bvid: String): JsonObject = buildJsonObject { put("title", "测试多P视频") }
            override suspend fun getPages(bvid: String): JsonArray {
                return buildPagesResponse(2)
            }
        }

        val result = MaterialCategorySyncBilibiliVideoPagesExecutor.execute(makeTask())
        assertTrue(result.isSuccess)

        val video1 = MaterialVideoService.repo.findById(bilibiliVideoId(testBvid, 1))
        assertNotNull(video1)
        assertEquals("P1 标题", video1.title)
        assertEquals(300, video1.duration)
        assertEquals("bilibili_video_pages", video1.sourceType)
        assertEquals(testBvid, video1.sourceId)

        val video2 = MaterialVideoService.repo.findById(bilibiliVideoId(testBvid, 2))
        assertNotNull(video2)
        assertEquals("P2 标题", video2.title)
        Unit
    }

    @Test
    fun ev6_display_name_fetched_when_empty() = runBlocking {
        createPlatformInfo(displayName = "")

        MaterialCategorySyncBilibiliVideoPagesExecutor.apiClient = object : MaterialCategorySyncBilibiliVideoPagesExecutor.ApiClient {
            override suspend fun getInfo(bvid: String): JsonObject = buildJsonObject {
                put("title", "多P视频标题")
            }
            override suspend fun getPages(bvid: String): JsonArray {
                return buildPagesResponse(2)
            }
        }

        val result = MaterialCategorySyncBilibiliVideoPagesExecutor.execute(makeTask())
        assertTrue(result.isSuccess)

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("多P视频标题", info.displayName)
        Unit
    }

    @Test
    fun ev7_display_name_fetch_failure_does_not_break_sync() = runBlocking {
        createPlatformInfo(displayName = "")

        MaterialCategorySyncBilibiliVideoPagesExecutor.apiClient = object : MaterialCategorySyncBilibiliVideoPagesExecutor.ApiClient {
            override suspend fun getInfo(bvid: String): JsonObject {
                throw RuntimeException("API unavailable")
            }
            override suspend fun getPages(bvid: String): JsonArray {
                return buildPagesResponse(1)
            }
        }

        val result = MaterialCategorySyncBilibiliVideoPagesExecutor.execute(makeTask())
        assertTrue(result.isSuccess)

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("", info.displayName)
        assertEquals(1, info.itemCount)
        Unit
    }

    @Test
    fun ev8_display_name_updated_when_already_set() = runBlocking {
        createPlatformInfo(displayName = "旧多P视频标题")

        MaterialCategorySyncBilibiliVideoPagesExecutor.apiClient = object : MaterialCategorySyncBilibiliVideoPagesExecutor.ApiClient {
            override suspend fun getInfo(bvid: String): JsonObject = buildJsonObject {
                put("title", "新多P视频标题")
            }
            override suspend fun getPages(bvid: String): JsonArray {
                return buildPagesResponse(1)
            }
        }

        val result = MaterialCategorySyncBilibiliVideoPagesExecutor.execute(makeTask())
        assertTrue(result.isSuccess)

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("新多P视频标题", info.displayName)
        Unit
    }
}
