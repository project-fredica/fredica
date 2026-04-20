package com.github.project_fredica.material_category

import com.github.project_fredica.material_category.db.MaterialCategoryDb
import com.github.project_fredica.material_category.model.MaterialCategoryDefaults
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
    fun mc12_ensureUncategorized_uses_correct_name() = runBlocking {
        categoryDb.ensureUncategorized("user-a")
        val cat = categoryDb.getById(MaterialCategoryDefaults.uncategorizedId("user-a"))
        assertNotNull(cat)
        assertEquals(MaterialCategoryDefaults.UNCATEGORIZED_NAME, cat.name)
        assertEquals("待分类", cat.name)
        Unit
    }

    @Test
    fun mc13_reconcileOrphanMaterials_moves_orphans_to_uncategorized() = runBlocking {
        val cat = categoryDb.create(ownerId = "user-a", name = "将删除", description = "")
        categoryDb.linkMaterials(listOf("mat-1", "mat-2"), listOf(cat.id), addedBy = "user")

        categoryDb.reconcileOrphanMaterials(cat.id, "user-a")

        val uncatId = MaterialCategoryDefaults.uncategorizedId("user-a")
        val uncatCat = categoryDb.getById(uncatId)
        assertNotNull(uncatCat, "待分类 category should be created")
        assertEquals("待分类", uncatCat.name)

        var count = 0
        db.useConnection { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM material_category_rel WHERE category_id = ?").use { ps ->
                ps.setString(1, uncatId)
                ps.executeQuery().use { rs -> if (rs.next()) count = rs.getInt(1) }
            }
        }
        assertEquals(2, count, "Both orphan materials should be linked to 待分类")
        Unit
    }

    @Test
    fun mc14_reconcileOrphanMaterials_skips_when_no_orphans() = runBlocking {
        val cat1 = categoryDb.create(ownerId = "user-a", name = "分类A", description = "")
        val cat2 = categoryDb.create(ownerId = "user-a", name = "分类B", description = "")
        categoryDb.linkMaterials(listOf("mat-1"), listOf(cat1.id, cat2.id), addedBy = "user")

        categoryDb.reconcileOrphanMaterials(cat1.id, "user-a")

        val uncatId = MaterialCategoryDefaults.uncategorizedId("user-a")
        val uncatCat = categoryDb.getById(uncatId)
        assertNull(uncatCat, "待分类 category should NOT be created when there are no orphans")
        Unit
    }

    @Test
    fun mc15_reconcileOrphanMaterials_only_moves_true_orphans() = runBlocking {
        val cat1 = categoryDb.create(ownerId = "user-a", name = "分类A", description = "")
        val cat2 = categoryDb.create(ownerId = "user-a", name = "分类B", description = "")
        categoryDb.linkMaterials(listOf("mat-1"), listOf(cat1.id, cat2.id), addedBy = "user")
        categoryDb.linkMaterials(listOf("mat-2"), listOf(cat1.id), addedBy = "user")

        categoryDb.reconcileOrphanMaterials(cat1.id, "user-a")

        val uncatId = MaterialCategoryDefaults.uncategorizedId("user-a")
        var uncatCount = 0
        db.useConnection { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM material_category_rel WHERE category_id = ?").use { ps ->
                ps.setString(1, uncatId)
                ps.executeQuery().use { rs -> if (rs.next()) uncatCount = rs.getInt(1) }
            }
        }
        assertEquals(1, uncatCount, "Only mat-2 (true orphan) should be moved to 待分类")

        var mat1InCat2 = false
        db.useConnection { conn ->
            conn.prepareStatement("SELECT 1 FROM material_category_rel WHERE material_id = ? AND category_id = ?").use { ps ->
                ps.setString(1, "mat-1")
                ps.setString(2, cat2.id)
                ps.executeQuery().use { rs -> mat1InCat2 = rs.next() }
            }
        }
        assertTrue(mat1InCat2, "mat-1 should still be in cat2")
        Unit
    }

    @Test
    fun mc16_ensureUncategorized_idempotent() = runBlocking {
        categoryDb.ensureUncategorized("user-a")
        categoryDb.ensureUncategorized("user-a")
        val cat = categoryDb.getById(MaterialCategoryDefaults.uncategorizedId("user-a"))
        assertNotNull(cat)
        assertEquals("待分类", cat.name)
        Unit
    }

    @Test
    fun mc17_close_view_cascades_add_delete() = runBlocking {
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

    private fun createMaterialTable() {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS material (
                        id          TEXT PRIMARY KEY,
                        type        TEXT NOT NULL,
                        title       TEXT NOT NULL DEFAULT '',
                        source_type TEXT NOT NULL DEFAULT '',
                        source_id   TEXT NOT NULL DEFAULT '',
                        cover_url   TEXT NOT NULL DEFAULT '',
                        description TEXT NOT NULL DEFAULT '',
                        extra       TEXT NOT NULL DEFAULT '{}',
                        created_at  INTEGER NOT NULL,
                        updated_at  INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }
    }

    private fun insertMaterial(id: String) {
        val now = System.currentTimeMillis() / 1000L
        db.useConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO material (id, type, title, created_at, updated_at) VALUES (?, 'video', '', ?, ?)"
            ).use { ps ->
                ps.setString(1, id)
                ps.setLong(2, now)
                ps.setLong(3, now)
                ps.executeUpdate()
            }
        }
    }

    @Test
    fun mc18_reconcileAllOrphanMaterials_assigns_to_uncategorized() = runBlocking {
        createMaterialTable()
        insertMaterial("mat-1")
        insertMaterial("mat-2")
        insertMaterial("mat-3")
        val cat = categoryDb.create(ownerId = "user-a", name = "分类A", description = "")
        categoryDb.linkMaterials(listOf("mat-1"), listOf(cat.id), addedBy = "user")

        categoryDb.reconcileAllOrphanMaterials()

        val uncatId = MaterialCategoryDefaults.uncategorizedId("user-a")
        val uncatCat = categoryDb.getById(uncatId)
        assertNotNull(uncatCat)
        assertEquals("待分类", uncatCat.name)

        var uncatCount = 0
        db.useConnection { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM material_category_rel WHERE category_id = ?").use { ps ->
                ps.setString(1, uncatId)
                ps.executeQuery().use { rs -> if (rs.next()) uncatCount = rs.getInt(1) }
            }
        }
        assertEquals(2, uncatCount, "mat-2 and mat-3 should be in 待分类")
        Unit
    }

    @Test
    fun mc19_reconcileAllOrphanMaterials_noop_when_no_orphans() = runBlocking {
        createMaterialTable()
        insertMaterial("mat-1")
        val cat = categoryDb.create(ownerId = "user-a", name = "分类A", description = "")
        categoryDb.linkMaterials(listOf("mat-1"), listOf(cat.id), addedBy = "user")

        categoryDb.reconcileAllOrphanMaterials()

        val uncatId = MaterialCategoryDefaults.uncategorizedId("user-a")
        val uncatCat = categoryDb.getById(uncatId)
        assertNull(uncatCat, "待分类 should NOT be created when there are no orphans")
        Unit
    }
}
