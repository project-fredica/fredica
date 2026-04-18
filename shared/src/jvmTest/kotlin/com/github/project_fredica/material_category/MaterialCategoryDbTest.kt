package com.github.project_fredica.material_category

import com.github.project_fredica.material_category.db.MaterialCategoryDb
import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.*

class MaterialCategoryDbTest {
    private lateinit var db: Database
    private lateinit var categoryDb: MaterialCategoryDb
    private lateinit var tmpFile: File

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_mc_db_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        categoryDb = MaterialCategoryDb(db)
        runBlocking { categoryDb.initialize() }
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    @Test
    fun mc1_create_category_sets_owner() = runBlocking {
        val cat = categoryDb.create(ownerId = "user-a", name = "学习", description = "desc")
        assertEquals("user-a", cat.ownerId)
        assertFalse(cat.allowOthersView)
        assertFalse(cat.allowOthersAdd)
        assertFalse(cat.allowOthersDelete)
        assertEquals(0, cat.materialCount)
        assertTrue(cat.isMine)
        Unit
    }

    @Test
    fun mc2_different_users_same_name() = runBlocking {
        val catA = categoryDb.create(ownerId = "user-a", name = "学习", description = "")
        val catB = categoryDb.create(ownerId = "user-b", name = "学习", description = "")
        assertNotEquals(catA.id, catB.id)
        assertEquals("user-a", catA.ownerId)
        assertEquals("user-b", catB.ownerId)
        Unit
    }

    @Test
    fun mc3_same_user_same_name_throws() = runBlocking {
        categoryDb.create(ownerId = "user-a", name = "学习", description = "")
        val threw = try {
            categoryDb.create(ownerId = "user-a", name = "学习", description = "")
            false
        } catch (_: Exception) {
            true
        }
        assertTrue(threw, "Expected UNIQUE constraint violation")
        Unit
    }

    @Test
    fun mc4_listForUser_includes_public_excludes_private() = runBlocking {
        categoryDb.create(ownerId = "user-a", name = "A-private", description = "")
        val bPublic = categoryDb.create(ownerId = "user-b", name = "B-public", description = "")
        categoryDb.create(ownerId = "user-b", name = "B-private", description = "")
        categoryDb.update(bPublic.id, "user-b", allowOthersView = true)

        val listA = categoryDb.listForUser("user-a")
        val names = listA.map { it.name }
        assertTrue("A-private" in names, "Should include own category")
        assertTrue("B-public" in names, "Should include B's public category")
        assertFalse("B-private" in names, "Should not include B's private category")
        Unit
    }

    @Test
    fun mc5_listMine_only_own() = runBlocking {
        categoryDb.create(ownerId = "user-a", name = "A-cat", description = "")
        val bPub = categoryDb.create(ownerId = "user-b", name = "B-pub", description = "")
        categoryDb.update(bPub.id, "user-b", allowOthersView = true)

        val mine = categoryDb.listMine("user-a")
        assertEquals(1, mine.size)
        assertEquals("A-cat", mine[0].name)
        Unit
    }

    @Test
    fun mc6_delete_by_non_owner_fails() = runBlocking {
        val cat = categoryDb.create(ownerId = "user-a", name = "test", description = "")
        val deleted = categoryDb.deleteById(cat.id, "user-b")
        assertFalse(deleted)
        val still = categoryDb.getById(cat.id)
        assertNotNull(still)
        Unit
    }

    @Test
    fun mc7_delete_by_owner_cascades_rel() = runBlocking {
        val cat = categoryDb.create(ownerId = "user-a", name = "test", description = "")
        categoryDb.linkMaterials(listOf("mat-1", "mat-2"), listOf(cat.id), addedBy = "user")
        val deleted = categoryDb.deleteById(cat.id, "user-a")
        assertTrue(deleted)
        assertNull(categoryDb.getById(cat.id))
        var relCount = 0
        db.useConnection { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM material_category_rel WHERE category_id = ?").use { ps ->
                ps.setString(1, cat.id)
                ps.executeQuery().use { rs -> if (rs.next()) relCount = rs.getInt(1) }
            }
        }
        assertEquals(0, relCount)
        Unit
    }

    @Test
    fun mc8_update_allow_others_view() = runBlocking {
        val cat = categoryDb.create(ownerId = "user-a", name = "test", description = "")
        val updated = categoryDb.update(cat.id, "user-a", allowOthersView = true)
        assertTrue(updated)
        val fetched = categoryDb.getById(cat.id)
        assertNotNull(fetched)
        assertTrue(fetched.allowOthersView)
        assertTrue(fetched.updatedAt >= cat.updatedAt)
        Unit
    }

    @Test
    fun mc9_update_by_non_owner_fails() = runBlocking {
        val cat = categoryDb.create(ownerId = "user-a", name = "test", description = "")
        val updated = categoryDb.update(cat.id, "user-b", name = "hacked")
        assertFalse(updated)
        val fetched = categoryDb.getById(cat.id)
        assertNotNull(fetched)
        assertEquals("test", fetched.name)
        Unit
    }

    @Test
    fun mc10_listForUser_isMine_field() = runBlocking {
        categoryDb.create(ownerId = "user-a", name = "A-cat", description = "")
        val bPub = categoryDb.create(ownerId = "user-b", name = "B-pub", description = "")
        categoryDb.update(bPub.id, "user-b", allowOthersView = true)

        val listA = categoryDb.listForUser("user-a")
        val aCat = listA.find { it.name == "A-cat" }
        val bCat = listA.find { it.name == "B-pub" }
        assertNotNull(aCat)
        assertNotNull(bCat)
        assertTrue(aCat.isMine)
        assertFalse(bCat.isMine)
        Unit
    }

    @Test
    fun mc11_listForUser_materialCount() = runBlocking {
        val cat = categoryDb.create(ownerId = "user-a", name = "test", description = "")
        categoryDb.linkMaterials(listOf("mat-1", "mat-2", "mat-3"), listOf(cat.id), addedBy = "user")

        val list = categoryDb.listForUser("user-a")
        val found = list.find { it.id == cat.id }
        assertNotNull(found)
        assertEquals(3, found.materialCount)
        Unit
    }

    @Test
    fun mc12_close_view_cascades_add_delete() = runBlocking {
        val cat = categoryDb.create(ownerId = "user-a", name = "test", description = "")
        categoryDb.update(cat.id, "user-a", allowOthersView = true, allowOthersAdd = true, allowOthersDelete = true)
        val before = categoryDb.getById(cat.id)
        assertNotNull(before)
        assertTrue(before.allowOthersView)
        assertTrue(before.allowOthersAdd)
        assertTrue(before.allowOthersDelete)

        categoryDb.update(cat.id, "user-a", allowOthersView = false)
        val after = categoryDb.getById(cat.id)
        assertNotNull(after)
        assertFalse(after.allowOthersView)
        assertFalse(after.allowOthersAdd)
        assertFalse(after.allowOthersDelete)
        Unit
    }
}
