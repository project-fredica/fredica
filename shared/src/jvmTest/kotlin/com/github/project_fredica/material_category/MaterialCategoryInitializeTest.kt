package com.github.project_fredica.material_category

import com.github.project_fredica.material_category.db.MaterialCategoryDb
import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class MaterialCategoryInitializeTest {
    private lateinit var db: Database
    private lateinit var tmpFile: File

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_mc_init_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    private fun tableColumns(tableName: String): List<String> {
        val cols = mutableListOf<String>()
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA table_info($tableName)").use { rs ->
                    while (rs.next()) cols.add(rs.getString("name"))
                }
            }
        }
        return cols
    }

    private fun indexNames(): List<String> {
        val names = mutableListOf<String>()
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'").use { rs ->
                    while (rs.next()) names.add(rs.getString("name"))
                }
            }
        }
        return names
    }

    @Test
    fun in1_initialize_creates_all_tables() = runBlocking {
        val categoryDb = MaterialCategoryDb(db)
        categoryDb.initialize()

        val simpleCols = tableColumns("material_category_simple")
        assertTrue("id" in simpleCols)
        assertTrue("owner_id" in simpleCols)
        assertTrue("name" in simpleCols)
        assertTrue("allow_others_view" in simpleCols)
        assertTrue("allow_others_add" in simpleCols)
        assertTrue("allow_others_delete" in simpleCols)

        val relCols = tableColumns("material_category_rel")
        assertTrue("material_id" in relCols)
        assertTrue("category_id" in relCols)
        assertTrue("added_by" in relCols)
        assertTrue("added_at" in relCols)

        val syncInfoCols = tableColumns("material_category_sync_platform_info")
        assertTrue("id" in syncInfoCols)
        assertTrue("sync_type" in syncInfoCols)
        assertTrue("platform_id" in syncInfoCols)
        assertTrue("platform_config" in syncInfoCols)
        assertTrue("sync_state" in syncInfoCols)
        assertTrue("fail_count" in syncInfoCols)

        val userConfigCols = tableColumns("material_category_sync_user_config")
        assertTrue("id" in userConfigCols)
        assertTrue("platform_info_id" in userConfigCols)
        assertTrue("user_id" in userConfigCols)
        assertTrue("cron_expr" in userConfigCols)
        assertTrue("freshness_window_sec" in userConfigCols)

        val syncItemCols = tableColumns("material_category_sync_item")
        assertTrue("id" in syncItemCols)
        assertTrue("platform_info_id" in syncItemCols)
        assertTrue("material_id" in syncItemCols)
        assertTrue("platform_item_id" in syncItemCols)
        assertTrue("synced_at" in syncItemCols)

        val auditCols = tableColumns("material_category_audit_log")
        assertTrue("id" in auditCols)
        assertTrue("category_id" in auditCols)
        assertTrue("user_id" in auditCols)
        assertTrue("action" in auditCols)
        assertTrue("detail" in auditCols)
        assertTrue("created_at" in auditCols)
        Unit
    }

    @Test
    fun in2_initialize_idempotent() = runBlocking {
        val categoryDb = MaterialCategoryDb(db)
        categoryDb.initialize()
        categoryDb.initialize()
        val cols = tableColumns("material_category_simple")
        assertTrue("id" in cols)
        Unit
    }

    @Test
    fun in3_unique_owner_name_constraint() = runBlocking {
        val categoryDb = MaterialCategoryDb(db)
        categoryDb.initialize()
        categoryDb.create(ownerId = "user-1", name = "学习", description = "")
        val threw = try {
            categoryDb.create(ownerId = "user-1", name = "学习", description = "")
            false
        } catch (_: Exception) {
            true
        }
        assertTrue(threw, "Expected UNIQUE constraint violation for same owner + same name")
        Unit
    }

    @Test
    fun in4_rel_composite_pk() = runBlocking {
        val categoryDb = MaterialCategoryDb(db)
        categoryDb.initialize()
        val cat = categoryDb.create(ownerId = "user-1", name = "test", description = "")
        categoryDb.linkMaterials(listOf("mat-1"), listOf(cat.id), addedBy = "user")
        categoryDb.linkMaterials(listOf("mat-1"), listOf(cat.id), addedBy = "user")
        // INSERT OR IGNORE means no exception, but only one row
        var count = 0
        db.useConnection { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM material_category_rel WHERE material_id = ? AND category_id = ?").use { ps ->
                ps.setString(1, "mat-1")
                ps.setString(2, cat.id)
                ps.executeQuery().use { rs ->
                    if (rs.next()) count = rs.getInt(1)
                }
            }
        }
        assertTrue(count == 1, "Expected exactly 1 rel row, got $count")
        Unit
    }

    @Test
    fun in5_indexes_created() = runBlocking {
        val categoryDb = MaterialCategoryDb(db)
        categoryDb.initialize()
        val names = indexNames()
        assertTrue("idx_category_simple_public" in names, "Missing idx_category_simple_public")
        assertTrue("idx_sync_platform_info_state_time" in names, "Missing idx_sync_platform_info_state_time")
        assertTrue("idx_sync_platform_info_category" in names, "Missing idx_sync_platform_info_category")
        assertTrue("idx_audit_log_category" in names, "Missing idx_audit_log_category")
        Unit
    }
}
