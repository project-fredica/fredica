package com.github.project_fredica.material_category

import com.github.project_fredica.material_category.db.MaterialCategoryDb
import com.github.project_fredica.material_category.db.MaterialCategorySyncPlatformInfoDb
import com.github.project_fredica.material_category.model.MaterialCategorySyncPlatformInfo
import com.github.project_fredica.material_category.service.MaterialCategorySyncPlatformInfoService
import com.github.project_fredica.material_category.service.MaterialCategorySyncScheduler
import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.*

class MaterialCategorySyncSchedulerTest {
    private lateinit var db: Database
    private lateinit var tmpFile: File
    private lateinit var categoryDb: MaterialCategoryDb
    private lateinit var platformInfoDb: MaterialCategorySyncPlatformInfoDb

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_scheduler_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        categoryDb = MaterialCategoryDb(db)
        platformInfoDb = MaterialCategorySyncPlatformInfoDb(db)
        runBlocking { categoryDb.initialize() }
        MaterialCategorySyncPlatformInfoService.initialize(platformInfoDb)
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    private fun createSyncingPlatformInfo(
        id: String,
        updatedAt: Long,
    ) = runBlocking {
        val cat = categoryDb.create("user-a", "cat-$id", "")
        platformInfoDb.create(
            MaterialCategorySyncPlatformInfo(
                id = id,
                syncType = "bilibili_favorite",
                platformId = "fav_$id",
                categoryId = cat.id,
                syncState = "syncing",
                createdAt = updatedAt,
                updatedAt = updatedAt,
            )
        )
    }

    @Test
    fun sy15_stale_syncing_record_gets_reset_to_failed() = runBlocking {
        val now = System.currentTimeMillis() / 1000L
        val staleTime = now - 3600 // 1 hour ago, well past 30min timeout
        createSyncingPlatformInfo("pi-stale", staleTime)

        MaterialCategorySyncScheduler.recoverStale(timeoutSec = 1800)

        val updated = platformInfoDb.getById("pi-stale")!!
        assertEquals("failed", updated.syncState)
        assertEquals("同步超时", updated.lastError)
        assertEquals(1, updated.failCount)
        Unit
    }

    @Test
    fun sy16_recent_syncing_record_stays_syncing() = runBlocking {
        val now = System.currentTimeMillis() / 1000L
        val recentTime = now - 60 // 1 minute ago, well within timeout
        createSyncingPlatformInfo("pi-recent", recentTime)

        MaterialCategorySyncScheduler.recoverStale(timeoutSec = 1800)

        val updated = platformInfoDb.getById("pi-recent")!!
        assertEquals("syncing", updated.syncState)
        assertNull(updated.lastError)
        assertEquals(0, updated.failCount)
        Unit
    }
}
