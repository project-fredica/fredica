package com.github.project_fredica.material_category

import com.github.project_fredica.material_category.db.MaterialCategoryDb
import com.github.project_fredica.material_category.db.MaterialCategorySyncItemDb
import com.github.project_fredica.material_category.db.MaterialCategorySyncPlatformInfoDb
import com.github.project_fredica.material_category.db.MaterialCategorySyncUserConfigDb
import com.github.project_fredica.material_category.model.MaterialCategorySyncItem
import com.github.project_fredica.material_category.model.MaterialCategorySyncPlatformInfo
import com.github.project_fredica.material_category.model.MaterialCategorySyncUserConfig
import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.*

class MaterialCategorySyncPlatformInfoDbTest {
    private lateinit var db: Database
    private lateinit var platformInfoDb: MaterialCategorySyncPlatformInfoDb
    private lateinit var syncItemDb: MaterialCategorySyncItemDb
    private lateinit var userConfigDb: MaterialCategorySyncUserConfigDb
    private lateinit var tmpFile: File

    private val now = System.currentTimeMillis() / 1000L

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_sync_pi_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        val categoryDb = MaterialCategoryDb(db)
        runBlocking { categoryDb.initialize() }
        platformInfoDb = MaterialCategorySyncPlatformInfoDb(db)
        syncItemDb = MaterialCategorySyncItemDb(db)
        userConfigDb = MaterialCategorySyncUserConfigDb(db)
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    private fun makeInfo(
        id: String = "pi-1",
        syncType: String = "bilibili_favorite",
        platformId: String = "12345",
        categoryId: String = "cat-1",
    ) = MaterialCategorySyncPlatformInfo(
        id = id,
        syncType = syncType,
        platformId = platformId,
        categoryId = categoryId,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun sp1_create_and_getById() = runBlocking {
        val info = makeInfo()
        platformInfoDb.create(info)
        val fetched = platformInfoDb.getById("pi-1")
        assertNotNull(fetched)
        assertEquals("bilibili_favorite", fetched.syncType)
        assertEquals("12345", fetched.platformId)
        assertEquals("cat-1", fetched.categoryId)
        assertEquals("idle", fetched.syncState)
        assertEquals(0, fetched.failCount)
        assertNull(fetched.lastSyncedAt)
        Unit
    }

    @Test
    fun sp2_duplicate_sync_type_platform_id_throws() = runBlocking {
        platformInfoDb.create(makeInfo(id = "pi-1", syncType = "bilibili_favorite", platformId = "12345"))
        val threw = try {
            platformInfoDb.create(makeInfo(id = "pi-2", syncType = "bilibili_favorite", platformId = "12345"))
            false
        } catch (_: Exception) {
            true
        }
        assertTrue(threw, "Expected UNIQUE constraint violation for same sync_type + platform_id")
        Unit
    }

    @Test
    fun sp3_different_sync_type_same_platform_id() = runBlocking {
        platformInfoDb.create(makeInfo(id = "pi-1", syncType = "bilibili_favorite", platformId = "12345"))
        platformInfoDb.create(makeInfo(id = "pi-2", syncType = "bilibili_uploader", platformId = "12345"))
        val a = platformInfoDb.getById("pi-1")
        val b = platformInfoDb.getById("pi-2")
        assertNotNull(a)
        assertNotNull(b)
        assertEquals("bilibili_favorite", a.syncType)
        assertEquals("bilibili_uploader", b.syncType)
        Unit
    }

    @Test
    fun sp4_delete_cascades_user_config_and_sync_item() = runBlocking {
        platformInfoDb.create(makeInfo(id = "pi-1"))
        userConfigDb.create(MaterialCategorySyncUserConfig(
            id = "uc-1", platformInfoId = "pi-1", userId = "user-1",
            createdAt = now, updatedAt = now,
        ))
        syncItemDb.upsert(MaterialCategorySyncItem(
            id = "si-1", platformInfoId = "pi-1", materialId = "mat-1",
            platformItemId = "bv123", syncedAt = now,
        ))

        val deleted = platformInfoDb.deleteById("pi-1")
        assertTrue(deleted)
        assertNull(platformInfoDb.getById("pi-1"))
        assertEquals(0, syncItemDb.countByPlatformInfo("pi-1"))
        val configs = userConfigDb.listByPlatformInfo("pi-1")
        assertTrue(configs.isEmpty())
        Unit
    }

    @Test
    fun sp5_updateAfterSyncSuccess() = runBlocking {
        platformInfoDb.create(makeInfo(id = "pi-1"))
        platformInfoDb.updateAfterSyncSuccess(
            id = "pi-1", syncCursor = "cursor-abc", lastSyncedAt = now + 100, itemCount = 42,
        )
        val fetched = platformInfoDb.getById("pi-1")
        assertNotNull(fetched)
        assertEquals("cursor-abc", fetched.syncCursor)
        assertEquals(now + 100, fetched.lastSyncedAt)
        assertEquals(42, fetched.itemCount)
        assertEquals("idle", fetched.syncState)
        assertEquals(0, fetched.failCount)
        assertNull(fetched.lastError)
        Unit
    }

    @Test
    fun sp6_updateAfterSyncFailure() = runBlocking {
        platformInfoDb.create(makeInfo(id = "pi-1"))
        platformInfoDb.updateAfterSyncFailure("pi-1", "network timeout")
        val fetched = platformInfoDb.getById("pi-1")
        assertNotNull(fetched)
        assertEquals("failed", fetched.syncState)
        assertEquals(1, fetched.failCount)
        assertEquals("network timeout", fetched.lastError)

        platformInfoDb.updateAfterSyncFailure("pi-1", "second failure")
        val fetched2 = platformInfoDb.getById("pi-1")
        assertNotNull(fetched2)
        assertEquals(2, fetched2.failCount)
        assertEquals("second failure", fetched2.lastError)
        Unit
    }

    @Test
    fun sp8_findByCategoryId() = runBlocking {
        platformInfoDb.create(makeInfo(id = "pi-1", categoryId = "cat-1"))
        platformInfoDb.create(makeInfo(id = "pi-2", syncType = "bilibili_uploader", platformId = "999", categoryId = "cat-2"))
        val found = platformInfoDb.findByCategoryId("cat-1")
        assertNotNull(found)
        assertEquals("pi-1", found.id)
        assertNull(platformInfoDb.findByCategoryId("cat-nonexistent"))
        Unit
    }

    @Test
    fun sp9_findByPlatformKey() = runBlocking {
        platformInfoDb.create(makeInfo(id = "pi-1", syncType = "bilibili_favorite", platformId = "12345"))
        val found = platformInfoDb.findByPlatformKey("bilibili_favorite", "12345")
        assertNotNull(found)
        assertEquals("pi-1", found.id)
        assertNull(platformInfoDb.findByPlatformKey("bilibili_favorite", "99999"))
        assertNull(platformInfoDb.findByPlatformKey("bilibili_uploader", "12345"))
        Unit
    }

    @Test
    fun sp_setSyncState() = runBlocking {
        platformInfoDb.create(makeInfo(id = "pi-1"))
        platformInfoDb.setSyncState("pi-1", "syncing")
        val fetched = platformInfoDb.getById("pi-1")
        assertNotNull(fetched)
        assertEquals("syncing", fetched.syncState)
        Unit
    }

    @Test
    fun sp_updateDisplayName() = runBlocking {
        platformInfoDb.create(makeInfo(id = "pi-1"))
        platformInfoDb.updateDisplayName("pi-1", "我的收藏夹")
        val fetched = platformInfoDb.getById("pi-1")
        assertNotNull(fetched)
        assertEquals("我的收藏夹", fetched.displayName)
        Unit
    }
}
