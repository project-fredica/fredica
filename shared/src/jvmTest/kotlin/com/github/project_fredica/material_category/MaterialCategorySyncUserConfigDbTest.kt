package com.github.project_fredica.material_category

import com.github.project_fredica.material_category.db.MaterialCategoryDb
import com.github.project_fredica.material_category.db.MaterialCategorySyncPlatformInfoDb
import com.github.project_fredica.material_category.db.MaterialCategorySyncUserConfigDb
import com.github.project_fredica.material_category.model.MaterialCategorySyncPlatformInfo
import com.github.project_fredica.material_category.model.MaterialCategorySyncUserConfig
import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.*

class MaterialCategorySyncUserConfigDbTest {
    private lateinit var db: Database
    private lateinit var userConfigDb: MaterialCategorySyncUserConfigDb
    private lateinit var platformInfoDb: MaterialCategorySyncPlatformInfoDb
    private lateinit var tmpFile: File

    private val now = System.currentTimeMillis() / 1000L

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_sync_uc_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        runBlocking { MaterialCategoryDb(db).initialize() }
        userConfigDb = MaterialCategorySyncUserConfigDb(db)
        platformInfoDb = MaterialCategorySyncPlatformInfoDb(db)
        runBlocking {
            platformInfoDb.create(MaterialCategorySyncPlatformInfo(
                id = "pi-1", syncType = "bilibili_favorite", platformId = "12345",
                categoryId = "cat-1", createdAt = now, updatedAt = now,
            ))
        }
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    private fun makeConfig(
        id: String = "uc-1",
        platformInfoId: String = "pi-1",
        userId: String = "user-1",
        enabled: Boolean = true,
        cronExpr: String = "0 */6 * * *",
        freshnessWindowSec: Int = 3600,
    ) = MaterialCategorySyncUserConfig(
        id = id, platformInfoId = platformInfoId, userId = userId,
        enabled = enabled, cronExpr = cronExpr, freshnessWindowSec = freshnessWindowSec,
        createdAt = now, updatedAt = now,
    )

    @Test
    fun uc1_create_and_getById() = runBlocking {
        userConfigDb.create(makeConfig())
        val fetched = userConfigDb.getById("uc-1")
        assertNotNull(fetched)
        assertEquals("pi-1", fetched.platformInfoId)
        assertEquals("user-1", fetched.userId)
        assertTrue(fetched.enabled)
        assertEquals("0 */6 * * *", fetched.cronExpr)
        assertEquals(3600, fetched.freshnessWindowSec)
        Unit
    }

    @Test
    fun uc2_duplicate_platform_user_throws() = runBlocking {
        userConfigDb.create(makeConfig(id = "uc-1", userId = "user-1"))
        val threw = try {
            userConfigDb.create(makeConfig(id = "uc-2", userId = "user-1"))
            false
        } catch (_: Exception) {
            true
        }
        assertTrue(threw, "Expected UNIQUE constraint violation for same platform_info_id + user_id")
        Unit
    }

    @Test
    fun uc3_different_users_same_platform() = runBlocking {
        userConfigDb.create(makeConfig(id = "uc-1", userId = "user-1"))
        userConfigDb.create(makeConfig(id = "uc-2", userId = "user-2"))
        val a = userConfigDb.getById("uc-1")
        val b = userConfigDb.getById("uc-2")
        assertNotNull(a)
        assertNotNull(b)
        assertEquals("user-1", a.userId)
        assertEquals("user-2", b.userId)
        Unit
    }

    @Test
    fun uc4_deleteById() = runBlocking {
        userConfigDb.create(makeConfig())
        val deleted = userConfigDb.deleteById("uc-1")
        assertTrue(deleted)
        assertNull(userConfigDb.getById("uc-1"))
        val pi = platformInfoDb.getById("pi-1")
        assertNotNull(pi, "Platform info should not be affected by user config deletion")
        Unit
    }

    @Test
    fun uc5_update_fields() = runBlocking {
        userConfigDb.create(makeConfig())
        val updated = userConfigDb.update(
            id = "uc-1", enabled = false, cronExpr = "0 0 * * *", freshnessWindowSec = 7200,
        )
        assertTrue(updated)
        val fetched = userConfigDb.getById("uc-1")
        assertNotNull(fetched)
        assertFalse(fetched.enabled)
        assertEquals("0 0 * * *", fetched.cronExpr)
        assertEquals(7200, fetched.freshnessWindowSec)
        assertTrue(fetched.updatedAt >= now)
        Unit
    }

    @Test
    fun uc5b_update_partial() = runBlocking {
        userConfigDb.create(makeConfig())
        userConfigDb.update(id = "uc-1", cronExpr = "*/30 * * * *")
        val fetched = userConfigDb.getById("uc-1")
        assertNotNull(fetched)
        assertTrue(fetched.enabled, "enabled should remain unchanged")
        assertEquals("*/30 * * * *", fetched.cronExpr)
        assertEquals(3600, fetched.freshnessWindowSec, "freshnessWindowSec should remain unchanged")
        Unit
    }

    @Test
    fun uc5c_update_nothing_returns_false() = runBlocking {
        userConfigDb.create(makeConfig())
        val updated = userConfigDb.update(id = "uc-1")
        assertFalse(updated)
        Unit
    }

    @Test
    fun uc6_findByPlatformInfoAndUser() = runBlocking {
        userConfigDb.create(makeConfig(id = "uc-1", userId = "user-1"))
        userConfigDb.create(makeConfig(id = "uc-2", userId = "user-2"))
        val found = userConfigDb.findByPlatformInfoAndUser("pi-1", "user-1")
        assertNotNull(found)
        assertEquals("uc-1", found.id)
        assertNull(userConfigDb.findByPlatformInfoAndUser("pi-1", "user-999"))
        Unit
    }

    @Test
    fun uc7_listByPlatformInfo() = runBlocking {
        userConfigDb.create(makeConfig(id = "uc-1", userId = "user-1"))
        userConfigDb.create(makeConfig(id = "uc-2", userId = "user-2"))
        val list = userConfigDb.listByPlatformInfo("pi-1")
        assertEquals(2, list.size)
        Unit
    }

    @Test
    fun uc8_listByUser() = runBlocking {
        userConfigDb.create(makeConfig(id = "uc-1", userId = "user-1"))
        val list = userConfigDb.listByUser("user-1")
        assertEquals(1, list.size)
        assertEquals("uc-1", list[0].id)
        assertTrue(userConfigDb.listByUser("user-999").isEmpty())
        Unit
    }

    @Test
    fun uc_subscriberCount() = runBlocking {
        assertEquals(0, userConfigDb.subscriberCount("pi-1"))
        userConfigDb.create(makeConfig(id = "uc-1", userId = "user-1"))
        assertEquals(1, userConfigDb.subscriberCount("pi-1"))
        userConfigDb.create(makeConfig(id = "uc-2", userId = "user-2"))
        assertEquals(2, userConfigDb.subscriberCount("pi-1"))
        Unit
    }

    @Test
    fun uc_deleteByPlatformInfoId() = runBlocking {
        userConfigDb.create(makeConfig(id = "uc-1", userId = "user-1"))
        userConfigDb.create(makeConfig(id = "uc-2", userId = "user-2"))
        val deleted = userConfigDb.deleteByPlatformInfoId("pi-1")
        assertEquals(2, deleted)
        assertTrue(userConfigDb.listByPlatformInfo("pi-1").isEmpty())
        Unit
    }
}
