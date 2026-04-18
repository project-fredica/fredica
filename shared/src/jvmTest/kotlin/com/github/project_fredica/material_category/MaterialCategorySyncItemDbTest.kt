package com.github.project_fredica.material_category

import com.github.project_fredica.material_category.db.MaterialCategoryDb
import com.github.project_fredica.material_category.db.MaterialCategorySyncItemDb
import com.github.project_fredica.material_category.model.MaterialCategorySyncItem
import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.*

class MaterialCategorySyncItemDbTest {
    private lateinit var db: Database
    private lateinit var syncItemDb: MaterialCategorySyncItemDb
    private lateinit var tmpFile: File

    private val now = System.currentTimeMillis() / 1000L

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_sync_item_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        runBlocking { MaterialCategoryDb(db).initialize() }
        syncItemDb = MaterialCategorySyncItemDb(db)
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    private fun makeItem(
        id: String = "si-1",
        platformInfoId: String = "pi-1",
        materialId: String = "mat-1",
        platformItemId: String = "BV1abc",
        syncedAt: Long = now,
    ) = MaterialCategorySyncItem(
        id = id, platformInfoId = platformInfoId, materialId = materialId,
        platformItemId = platformItemId, syncedAt = syncedAt,
    )

    @Test
    fun si1_upsert_and_list() = runBlocking {
        syncItemDb.upsert(makeItem())
        val list = syncItemDb.listByPlatformInfo("pi-1")
        assertEquals(1, list.size)
        assertEquals("si-1", list[0].id)
        assertEquals("mat-1", list[0].materialId)
        assertEquals("BV1abc", list[0].platformItemId)
        Unit
    }

    @Test
    fun si2_upsert_replaces_on_id_conflict() = runBlocking {
        syncItemDb.upsert(makeItem(id = "si-1", platformItemId = "old"))
        syncItemDb.upsert(makeItem(id = "si-1", platformItemId = "new"))
        val list = syncItemDb.listByPlatformInfo("pi-1")
        assertEquals(1, list.size)
        assertEquals("new", list[0].platformItemId)
        Unit
    }

    @Test
    fun si3_upsertBatch() = runBlocking {
        val items = listOf(
            makeItem(id = "si-1", materialId = "mat-1"),
            makeItem(id = "si-2", materialId = "mat-2"),
            makeItem(id = "si-3", materialId = "mat-3"),
        )
        syncItemDb.upsertBatch(items)
        assertEquals(3, syncItemDb.countByPlatformInfo("pi-1"))
        Unit
    }

    @Test
    fun si4_upsertBatch_empty() = runBlocking {
        syncItemDb.upsertBatch(emptyList())
        assertEquals(0, syncItemDb.countByPlatformInfo("pi-1"))
        Unit
    }

    @Test
    fun si5_countByPlatformInfo() = runBlocking {
        assertEquals(0, syncItemDb.countByPlatformInfo("pi-1"))
        syncItemDb.upsert(makeItem(id = "si-1", materialId = "mat-1"))
        syncItemDb.upsert(makeItem(id = "si-2", materialId = "mat-2"))
        assertEquals(2, syncItemDb.countByPlatformInfo("pi-1"))
        assertEquals(0, syncItemDb.countByPlatformInfo("pi-other"))
        Unit
    }

    @Test
    fun si6_deleteByPlatformInfoId() = runBlocking {
        syncItemDb.upsert(makeItem(id = "si-1", platformInfoId = "pi-1", materialId = "mat-1"))
        syncItemDb.upsert(makeItem(id = "si-2", platformInfoId = "pi-1", materialId = "mat-2"))
        syncItemDb.upsert(makeItem(id = "si-3", platformInfoId = "pi-2", materialId = "mat-3"))
        val deleted = syncItemDb.deleteByPlatformInfoId("pi-1")
        assertEquals(2, deleted)
        assertEquals(0, syncItemDb.countByPlatformInfo("pi-1"))
        assertEquals(1, syncItemDb.countByPlatformInfo("pi-2"))
        Unit
    }

    @Test
    fun si7_listByPlatformInfo_ordered_by_synced_at_desc() = runBlocking {
        syncItemDb.upsert(makeItem(id = "si-1", materialId = "mat-1", syncedAt = 1000))
        syncItemDb.upsert(makeItem(id = "si-2", materialId = "mat-2", syncedAt = 3000))
        syncItemDb.upsert(makeItem(id = "si-3", materialId = "mat-3", syncedAt = 2000))
        val list = syncItemDb.listByPlatformInfo("pi-1")
        assertEquals(3, list.size)
        assertEquals("si-2", list[0].id)
        assertEquals("si-3", list[1].id)
        assertEquals("si-1", list[2].id)
        Unit
    }
}
