package com.github.project_fredica.material_category

import com.github.project_fredica.apputil.bilibiliVideoId
import com.github.project_fredica.bilibili_account_pool.db.BilibiliAccountPoolDb
import com.github.project_fredica.bilibili_account_pool.service.BilibiliAccountPoolService
import com.github.project_fredica.db.AppConfigDb
import com.github.project_fredica.db.AppConfigService
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
import com.github.project_fredica.worker.TaskPauseResumeChannels
import com.github.project_fredica.worker.executors.MaterialCategorySyncBilibiliUploaderExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.ktorm.database.Database
import java.io.File
import kotlin.test.*

class MaterialCategorySyncBilibiliUploaderExecutorTest {
    private lateinit var db: Database
    private lateinit var tmpFile: File
    private lateinit var categoryDb: MaterialCategoryDb
    private lateinit var syncPlatformInfoDb: MaterialCategorySyncPlatformInfoDb
    private lateinit var syncItemDb: MaterialCategorySyncItemDb

    private val now = System.currentTimeMillis() / 1000L
    private val categoryId = "cat-1"
    private val platformInfoId = "pi-1"

    private var savedSyncClient: MaterialCategorySyncBilibiliUploaderExecutor.SyncClient? = null

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

        runBlocking {
            val appConfigDb = AppConfigDb(db)
            appConfigDb.initialize()
            AppConfigService.initialize(appConfigDb)
            val accountPoolDb = BilibiliAccountPoolDb(db)
            accountPoolDb.initialize()
            BilibiliAccountPoolService.initialize(accountPoolDb)
        }

        savedSyncClient = MaterialCategorySyncBilibiliUploaderExecutor.syncClient

        runBlocking {
            categoryDb.insertOrIgnore(
                com.github.project_fredica.material_category.model.MaterialCategory(
                    id = categoryId,
                    ownerId = "user-1",
                    name = "测试UP主",
                    description = "",
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
    }

    @AfterTest
    fun teardown() {
        savedSyncClient?.let { MaterialCategorySyncBilibiliUploaderExecutor.syncClient = it }
        tmpFile.delete()
    }

    private fun createPlatformInfo(
        id: String = platformInfoId,
        mid: Long = 12345L,
        syncCursor: String = "",
        syncState: String = "idle",
        displayName: String = "测试UP主",
    ) = runBlocking {
        val config = buildJsonObject { put("type", "bilibili_uploader"); put("mid", mid) }.toString()
        syncPlatformInfoDb.create(
            MaterialCategorySyncPlatformInfo(
                id = id,
                syncType = "bilibili_uploader",
                platformId = mid.toString(),
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
            type = "SYNC_BILIBILI_UPLOADER",
            workflowRunId = "wf-1",
            materialId = categoryId,
            status = "running",
            priority = 10,
            payload = payload,
            createdAt = now,
        )
    }

    private fun buildVideoItem(bvid: String, created: Long): JsonObject = buildJsonObject {
        put("bvid", bvid)
        put("title", "视频 $bvid")
        put("created", created)
        put("length", "05:00")
        put("pic", "https://example.com/$bvid.jpg")
    }

    private fun buildPageMessage(videos: List<JsonObject>, hasMore: Boolean, page: Int = 1, totalCount: Int = videos.size): String {
        return buildJsonObject {
            put("type", "page")
            put("page", page)
            put("videos", buildJsonArray { videos.forEach { add(it) } })
            put("has_more", hasMore)
            put("total_count", totalCount)
        }.toString()
    }

    private fun buildDoneMessage(totalPages: Int = 1, totalVideos: Int = 0): String {
        return buildJsonObject {
            put("type", "done")
            put("total_pages", totalPages)
            put("total_videos", totalVideos)
        }.toString()
    }

    private fun buildErrorMessage(error: String): String {
        return buildJsonObject {
            put("type", "error")
            put("error", error)
        }.toString()
    }

    private fun buildAccountSwitchMessage(from: String, to: String, reason: String = "412 风控"): String {
        return buildJsonObject {
            put("type", "account_switch")
            put("from", from)
            put("to", to)
            put("reason", reason)
        }.toString()
    }

    private fun makeSyncClient(
        infoResponse: JsonObject = buildJsonObject { put("name", "测试UP主") },
        infoError: Throwable? = null,
        rawMessages: List<String> = emptyList(),
        doneResult: String? = buildDoneMessage(),
    ): MaterialCategorySyncBilibiliUploaderExecutor.SyncClient {
        return object : MaterialCategorySyncBilibiliUploaderExecutor.SyncClient {
            override suspend fun getInfo(mid: String): JsonObject {
                if (infoError != null) throw infoError
                return infoResponse
            }

            override suspend fun runSync(
                paramJson: String,
                onRawMessage: suspend (String) -> Unit,
                onProgress: suspend (Int) -> Unit,
                cancelSignal: CompletableDeferred<Unit>,
                pauseResumeChannels: TaskPauseResumeChannels,
            ): String? {
                for (msg in rawMessages) {
                    onRawMessage(msg)
                }
                return doneResult
            }
        }
    }

    // eu1: 多页 page 消息 → DB 写入 MaterialVideo + SyncItem + linkMaterials
    @Test
    fun eu1_full_sync_from_messages() = runBlocking {
        createPlatformInfo()

        val page1Videos = listOf(
            buildVideoItem("BV1aaa", 1000L),
            buildVideoItem("BV1bbb", 900L),
            buildVideoItem("BV1ccc", 800L),
        )
        val page2Videos = listOf(
            buildVideoItem("BV1ddd", 700L),
            buildVideoItem("BV1eee", 600L),
        )

        MaterialCategorySyncBilibiliUploaderExecutor.syncClient = makeSyncClient(
            rawMessages = listOf(
                buildPageMessage(page1Videos, hasMore = true, page = 1, totalCount = 5),
                buildPageMessage(page2Videos, hasMore = false, page = 2, totalCount = 5),
            ),
        )

        val result = MaterialCategorySyncBilibiliUploaderExecutor.execute(makeTask())
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

    // eu2: page 消息 has_more=false → 更新 syncCursor
    @Test
    fun eu2_incremental_sync_cursor() = runBlocking {
        createPlatformInfo(syncCursor = "800")

        val videos = listOf(
            buildVideoItem("BV1new1", 1000L),
            buildVideoItem("BV1new2", 900L),
        )

        MaterialCategorySyncBilibiliUploaderExecutor.syncClient = makeSyncClient(
            rawMessages = listOf(
                buildPageMessage(videos, hasMore = false, page = 1, totalCount = 2),
            ),
        )

        val result = MaterialCategorySyncBilibiliUploaderExecutor.execute(makeTask())
        assertTrue(result.isSuccess)

        assertEquals(2, syncItemDb.countByPlatformInfo(platformInfoId))

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("idle", info.syncState)
        assertEquals("1000", info.syncCursor)
        assertEquals(2, info.itemCount)
        Unit
    }

    // eu3: done 消息 → syncState="idle", failCount=0
    @Test
    fun eu3_sync_success_resets_state() = runBlocking {
        createPlatformInfo(syncState = "idle")

        MaterialCategorySyncBilibiliUploaderExecutor.syncClient = makeSyncClient(
            rawMessages = listOf(
                buildPageMessage(listOf(buildVideoItem("BV1xxx", 500L)), hasMore = false),
            ),
        )

        val result = MaterialCategorySyncBilibiliUploaderExecutor.execute(makeTask())
        assertTrue(result.isSuccess)

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("idle", info.syncState)
        assertEquals(0, info.failCount)
        assertNull(info.lastError)
        Unit
    }

    // eu4: error 消息 → syncState="failed", lastError 记录
    @Test
    fun eu4_error_message_sets_failed() = runBlocking {
        createPlatformInfo()

        MaterialCategorySyncBilibiliUploaderExecutor.syncClient = object : MaterialCategorySyncBilibiliUploaderExecutor.SyncClient {
            override suspend fun getInfo(mid: String): JsonObject = buildJsonObject { put("name", "测试UP主") }
            override suspend fun runSync(
                paramJson: String,
                onRawMessage: suspend (String) -> Unit,
                onProgress: suspend (Int) -> Unit,
                cancelSignal: CompletableDeferred<Unit>,
                pauseResumeChannels: TaskPauseResumeChannels,
            ): String? {
                throw IllegalStateException("WebSocket task failed: 所有账号均触发风控，同步终止")
            }
        }

        val result = MaterialCategorySyncBilibiliUploaderExecutor.execute(makeTask())
        assertFalse(result.isSuccess)
        assertEquals("SYNC_ERROR", result.errorType)
        assertTrue(result.error!!.contains("所有账号均触发风控"))

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("failed", info.syncState)
        assertEquals(1, info.failCount)
        assertTrue(info.lastError!!.contains("所有账号均触发风控"))
        Unit
    }

    // eu5: page 消息中的视频 → MaterialVideo 字段正确
    @Test
    fun eu5_material_records_created() = runBlocking {
        createPlatformInfo()

        MaterialCategorySyncBilibiliUploaderExecutor.syncClient = makeSyncClient(
            rawMessages = listOf(
                buildPageMessage(listOf(buildVideoItem("BV1mat", 500L)), hasMore = false),
            ),
        )

        val result = MaterialCategorySyncBilibiliUploaderExecutor.execute(makeTask())
        assertTrue(result.isSuccess)

        val expectedId = bilibiliVideoId("BV1mat")
        val video = MaterialVideoService.repo.findById(expectedId)
        assertNotNull(video)
        assertEquals("视频 BV1mat", video.title)
        assertEquals("https://example.com/BV1mat.jpg", video.coverUrl)
        assertEquals("bilibili_uploader", video.sourceType)
        assertEquals("BV1mat", video.sourceId)
        Unit
    }

    // eu6: 调用 get-info REST 端点获取 UP主名
    @Test
    fun eu6_display_name_from_info() = runBlocking {
        createPlatformInfo(displayName = "")

        MaterialCategorySyncBilibiliUploaderExecutor.syncClient = makeSyncClient(
            infoResponse = buildJsonObject { put("name", "某某UP主") },
            rawMessages = listOf(
                buildPageMessage(listOf(buildVideoItem("BV1dn", 500L)), hasMore = false),
            ),
        )

        val result = MaterialCategorySyncBilibiliUploaderExecutor.execute(makeTask())
        assertTrue(result.isSuccess)

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("某某UP主", info.displayName)
        Unit
    }

    // eu7: get-info 失败不阻塞同步
    @Test
    fun eu7_info_failure_non_blocking() = runBlocking {
        createPlatformInfo(displayName = "")

        MaterialCategorySyncBilibiliUploaderExecutor.syncClient = makeSyncClient(
            infoError = RuntimeException("API unavailable"),
            rawMessages = listOf(
                buildPageMessage(listOf(buildVideoItem("BV1dn2", 500L)), hasMore = false),
            ),
        )

        val result = MaterialCategorySyncBilibiliUploaderExecutor.execute(makeTask())
        assertTrue(result.isSuccess)

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("", info.displayName)
        assertEquals(1, info.itemCount)
        Unit
    }

    // eu8: displayName 已有值时仍更新
    @Test
    fun eu8_display_name_updated() = runBlocking {
        createPlatformInfo(displayName = "旧UP主名")

        MaterialCategorySyncBilibiliUploaderExecutor.syncClient = makeSyncClient(
            infoResponse = buildJsonObject { put("name", "新UP主名") },
            rawMessages = listOf(
                buildPageMessage(listOf(buildVideoItem("BV1upd", 500L)), hasMore = false),
            ),
        )

        val result = MaterialCategorySyncBilibiliUploaderExecutor.execute(makeTask())
        assertTrue(result.isSuccess)

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("新UP主名", info.displayName)
        Unit
    }

    // eu9: account_switch 消息 → 日志记录（不影响同步结果）
    @Test
    fun eu9_account_switch_logged() = runBlocking {
        createPlatformInfo()

        val page1Videos = listOf(buildVideoItem("BV1p1", 1000L))
        val page2Videos = listOf(buildVideoItem("BV1p2", 800L))

        MaterialCategorySyncBilibiliUploaderExecutor.syncClient = makeSyncClient(
            rawMessages = listOf(
                buildPageMessage(page1Videos, hasMore = true, page = 1, totalCount = 2),
                buildAccountSwitchMessage(from = "匿名A", to = "登录B"),
                buildPageMessage(page2Videos, hasMore = false, page = 2, totalCount = 2),
            ),
        )

        val result = MaterialCategorySyncBilibiliUploaderExecutor.execute(makeTask())
        assertTrue(result.isSuccess, "Expected success but got: ${result.error}")

        assertEquals(2, syncItemDb.countByPlatformInfo(platformInfoId))

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("idle", info.syncState)
        assertEquals("1000", info.syncCursor)
        Unit
    }

    // eu10: WebSocket 错误（空账号池）→ ExecuteResult error
    @Test
    fun eu10_empty_account_pool_error() = runBlocking {
        createPlatformInfo()

        MaterialCategorySyncBilibiliUploaderExecutor.syncClient = object : MaterialCategorySyncBilibiliUploaderExecutor.SyncClient {
            override suspend fun getInfo(mid: String): JsonObject = buildJsonObject { put("name", "测试UP主") }
            override suspend fun runSync(
                paramJson: String,
                onRawMessage: suspend (String) -> Unit,
                onProgress: suspend (Int) -> Unit,
                cancelSignal: CompletableDeferred<Unit>,
                pauseResumeChannels: TaskPauseResumeChannels,
            ): String? {
                throw IllegalStateException("WebSocket task failed: 未配置任何B站账号")
            }
        }

        val result = MaterialCategorySyncBilibiliUploaderExecutor.execute(makeTask())
        assertFalse(result.isSuccess)
        assertEquals("SYNC_ERROR", result.errorType)
        assertTrue(result.error!!.contains("未配置任何B站账号"))

        val info = syncPlatformInfoDb.getById(platformInfoId)!!
        assertEquals("failed", info.syncState)
        Unit
    }
}
