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
import com.github.project_fredica.worker.executors.MaterialCategorySyncBilibiliSeriesExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.ktorm.database.Database
import java.io.File
import kotlin.test.*

class MaterialCategorySyncBilibiliSeriesExecutorTest {
    private lateinit var db: Database
    private lateinit var tmpFile: File
    private lateinit var categoryDb: MaterialCategoryDb
    private lateinit var syncPlatformInfoDb: MaterialCategorySyncPlatformInfoDb
    private lateinit var syncItemDb: MaterialCategorySyncItemDb

    private val now = System.currentTimeMillis() / 1000L
    private val categoryId = "cat-1"
    private val platformInfoId = "pi-1"

    private var savedApiClient: MaterialCategorySyncBilibiliSeriesExecutor.ApiClient? = null

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

        savedApiClient = MaterialCategorySyncBilibiliSeriesExecutor.apiClient

        runBlocking {
            categoryDb.insertOrIgnore(
                com.github.project_fredica.material_category.model.MaterialCategory(
                    id = categoryId,
                    ownerId = "user-1",
                    name = "测试系列",
                    description = "",
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
    }

    @AfterTest
    fun teardown() {
        savedApiClient?.let { MaterialCategorySyncBilibiliSeriesExecutor.apiClient = it }
        tmpFile.delete()
    }

    private fun createPlatformInfo(
        id: String = platformInfoId,
        seriesId: Long = 200001L,
        mid: Long = 12345L,
        syncState: String = "idle",
        displayName: String = "测试系列",
    ) = runBlocking {
        val config = buildJsonObject { put("type", "bilibili_series"); put("series_id", seriesId); put("mid", mid) }.toString()
        syncPlatformInfoDb.create(
            MaterialCategorySyncPlatformInfo(
                id = id,
                syncType = "bilibili_series",
                platformId = seriesId.toString(),
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
            type = "SYNC_BILIBILI_SERIES",
            workflowRunId = "wf-1",
            materialId = categoryId,
            status = "running",
            priority = 10,
            payload = payload,
            createdAt = now,
        )
    }

    private fun buildVideoItem(bvid: String): JsonObject = buildJsonObject {
        put("bvid", bvid)
        put("title", "视频 $bvid")
        put("pic", "https://example.com/$bvid.jpg")
        put("duration", 300)
        put("pubdate", 1000L)
    }

    private fun buildPageResponse(videos: List<JsonObject>, hasMore: Boolean): JsonObject = buildJsonObject {
        put("series_id", "200001")
        put("mid", "12345")
        put("page", 1)
        put("videos", buildJsonArray { videos.forEach { add(it) } })
        put("has_more", hasMore.toString())
    }

    @Test
    fun er1_full_sync_all_videos() = runBlocking {
        createPlatformInfo()

        val page1Videos = listOf(
            buildVideoItem("BV1aaa"),
            buildVideoItem("BV1bbb"),
            buildVideoItem("BV1ccc"),
        )
        val page2Videos = listOf(
            buildVideoItem("BV1ddd"),
        )

        MaterialCategorySyncBilibiliSeriesExecutor.apiClient = object : MaterialCategorySyncBilibiliSeriesExecutor.ApiClient {
            override suspend fun getMeta(seriesId: String, mid: String): JsonObject = buildJsonObject { put("name", "测试系列") }
            override suspend fun getPage(seriesId: String, mid: String, page: Int): JsonObject {
                assertEquals("200001", seriesId)
                assertEquals("12345", mid)
                return when (page) {
                    1 -> buildPageResponse(page1Videos, hasMore = true)
                    2 -> buildPageResponse(page2Videos, hasMore = false)
                    else -> error("Unexpected page: $page")
                }
            }
        }

        val result = MaterialCategorySyncBilibiliSeriesExecutor.execute(makeTask())
        assertTrue(result.isSuccess, "Expected success but got: ${result.error}")

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("idle", info.syncState)
        assertEquals(4, info.itemCount)
        assertEquals(0, info.failCount)
        assertNull(info.lastError)
        assertNotNull(info.lastSyncedAt)

        assertEquals(4, syncItemDb.countByPlatformInfo(platformInfoId))
        Unit
    }

    @Test
    fun er2_repeat_sync_idempotent() = runBlocking {
        createPlatformInfo()

        val videos = listOf(
            buildVideoItem("BV1aaa"),
            buildVideoItem("BV1bbb"),
        )

        val mockClient = object : MaterialCategorySyncBilibiliSeriesExecutor.ApiClient {
            override suspend fun getMeta(seriesId: String, mid: String): JsonObject = buildJsonObject { put("name", "测试系列") }
            override suspend fun getPage(seriesId: String, mid: String, page: Int): JsonObject {
                return buildPageResponse(videos, hasMore = false)
            }
        }
        MaterialCategorySyncBilibiliSeriesExecutor.apiClient = mockClient

        val result1 = MaterialCategorySyncBilibiliSeriesExecutor.execute(makeTask())
        assertTrue(result1.isSuccess)
        assertEquals(2, syncItemDb.countByPlatformInfo(platformInfoId))

        val result2 = MaterialCategorySyncBilibiliSeriesExecutor.execute(makeTask())
        assertTrue(result2.isSuccess)
        assertEquals(2, syncItemDb.countByPlatformInfo(platformInfoId))
        Unit
    }

    @Test
    fun er3_sync_success_resets_state() = runBlocking {
        createPlatformInfo(syncState = "idle")

        MaterialCategorySyncBilibiliSeriesExecutor.apiClient = object : MaterialCategorySyncBilibiliSeriesExecutor.ApiClient {
            override suspend fun getMeta(seriesId: String, mid: String): JsonObject = buildJsonObject { put("name", "测试系列") }
            override suspend fun getPage(seriesId: String, mid: String, page: Int): JsonObject {
                return buildPageResponse(
                    listOf(buildVideoItem("BV1xxx")),
                    hasMore = false,
                )
            }
        }

        val result = MaterialCategorySyncBilibiliSeriesExecutor.execute(makeTask())
        assertTrue(result.isSuccess)

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("idle", info.syncState)
        assertEquals(0, info.failCount)
        assertNull(info.lastError)
        Unit
    }

    @Test
    fun er4_api_failure_sets_failed_state() = runBlocking {
        createPlatformInfo()

        MaterialCategorySyncBilibiliSeriesExecutor.apiClient = object : MaterialCategorySyncBilibiliSeriesExecutor.ApiClient {
            override suspend fun getMeta(seriesId: String, mid: String): JsonObject = buildJsonObject { put("name", "测试系列") }
            override suspend fun getPage(seriesId: String, mid: String, page: Int): JsonObject {
                throw RuntimeException("Connection refused")
            }
        }

        val result = MaterialCategorySyncBilibiliSeriesExecutor.execute(makeTask())
        assertFalse(result.isSuccess)
        assertEquals("SYNC_ERROR", result.errorType)
        assertTrue(result.error!!.contains("Connection refused"))

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("failed", info.syncState)
        assertEquals(1, info.failCount)
        assertEquals("Connection refused", info.lastError)
        Unit
    }

    @Test
    fun er5_sync_creates_material_records() = runBlocking {
        createPlatformInfo()

        MaterialCategorySyncBilibiliSeriesExecutor.apiClient = object : MaterialCategorySyncBilibiliSeriesExecutor.ApiClient {
            override suspend fun getMeta(seriesId: String, mid: String): JsonObject = buildJsonObject { put("name", "测试系列") }
            override suspend fun getPage(seriesId: String, mid: String, page: Int): JsonObject {
                return buildPageResponse(
                    listOf(buildVideoItem("BV1mat")),
                    hasMore = false,
                )
            }
        }

        val result = MaterialCategorySyncBilibiliSeriesExecutor.execute(makeTask())
        assertTrue(result.isSuccess)

        val expectedId = bilibiliVideoId("BV1mat")
        val video = MaterialVideoService.repo.findById(expectedId)
        assertNotNull(video)
        assertEquals("视频 BV1mat", video.title)
        assertEquals("https://example.com/BV1mat.jpg", video.coverUrl)
        assertEquals(300, video.duration)
        assertEquals("bilibili_series", video.sourceType)
        assertEquals("BV1mat", video.sourceId)
        Unit
    }

    @Test
    fun er6_display_name_fetched_when_empty() = runBlocking {
        createPlatformInfo(displayName = "")

        MaterialCategorySyncBilibiliSeriesExecutor.apiClient = object : MaterialCategorySyncBilibiliSeriesExecutor.ApiClient {
            override suspend fun getMeta(seriesId: String, mid: String): JsonObject = buildJsonObject {
                put("name", "日常 vlog")
            }
            override suspend fun getPage(seriesId: String, mid: String, page: Int): JsonObject {
                return buildPageResponse(
                    listOf(buildVideoItem("BV1dn")),
                    hasMore = false,
                )
            }
        }

        val result = MaterialCategorySyncBilibiliSeriesExecutor.execute(makeTask())
        assertTrue(result.isSuccess)

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("日常 vlog", info.displayName)
        Unit
    }

    @Test
    fun er7_display_name_fetch_failure_does_not_break_sync() = runBlocking {
        createPlatformInfo(displayName = "")

        MaterialCategorySyncBilibiliSeriesExecutor.apiClient = object : MaterialCategorySyncBilibiliSeriesExecutor.ApiClient {
            override suspend fun getMeta(seriesId: String, mid: String): JsonObject {
                throw RuntimeException("API unavailable")
            }
            override suspend fun getPage(seriesId: String, mid: String, page: Int): JsonObject {
                return buildPageResponse(
                    listOf(buildVideoItem("BV1dn2")),
                    hasMore = false,
                )
            }
        }

        val result = MaterialCategorySyncBilibiliSeriesExecutor.execute(makeTask())
        assertTrue(result.isSuccess)

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("", info.displayName)
        assertEquals(1, info.itemCount)
        Unit
    }

    @Test
    fun er8_display_name_updated_when_already_set() = runBlocking {
        createPlatformInfo(displayName = "旧系列名")

        MaterialCategorySyncBilibiliSeriesExecutor.apiClient = object : MaterialCategorySyncBilibiliSeriesExecutor.ApiClient {
            override suspend fun getMeta(seriesId: String, mid: String): JsonObject = buildJsonObject {
                put("name", "新系列名")
            }
            override suspend fun getPage(seriesId: String, mid: String, page: Int): JsonObject {
                return buildPageResponse(
                    listOf(buildVideoItem("BV1upd")),
                    hasMore = false,
                )
            }
        }

        val result = MaterialCategorySyncBilibiliSeriesExecutor.execute(makeTask())
        assertTrue(result.isSuccess)

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("新系列名", info.displayName)
        Unit
    }
}
