package com.github.project_fredica.material_category

import com.github.project_fredica.material_category.db.MaterialCategoryAuditLogDb
import com.github.project_fredica.material_category.db.MaterialCategoryDb
import com.github.project_fredica.material_category.model.MaterialCategoryAuditLog
import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.*

class MaterialCategoryAuditLogDbTest {
    private lateinit var db: Database
    private lateinit var auditLogDb: MaterialCategoryAuditLogDb
    private lateinit var tmpFile: File

    private val now = System.currentTimeMillis() / 1000L

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_mc_audit_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        runBlocking { MaterialCategoryDb(db).initialize() }
        auditLogDb = MaterialCategoryAuditLogDb(db)
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    @Test
    fun al1_insert_and_listByCategoryId() = runBlocking {
        auditLogDb.insert(MaterialCategoryAuditLog(
            id = "log-1", categoryId = "cat-1", userId = "user-1",
            action = "CREATE", detail = "{}", createdAt = now,
        ))
        val list = auditLogDb.listByCategoryId("cat-1")
        assertEquals(1, list.size)
        assertEquals("log-1", list[0].id)
        assertEquals("CREATE", list[0].action)
        assertEquals("user-1", list[0].userId)
        Unit
    }

    @Test
    fun al2_listByCategoryId_ordered_desc() = runBlocking {
        auditLogDb.insert(MaterialCategoryAuditLog(
            id = "log-1", categoryId = "cat-1", userId = "user-1",
            action = "CREATE", createdAt = 1000,
        ))
        auditLogDb.insert(MaterialCategoryAuditLog(
            id = "log-2", categoryId = "cat-1", userId = "user-1",
            action = "UPDATE", createdAt = 3000,
        ))
        auditLogDb.insert(MaterialCategoryAuditLog(
            id = "log-3", categoryId = "cat-1", userId = "user-1",
            action = "DELETE", createdAt = 2000,
        ))
        val list = auditLogDb.listByCategoryId("cat-1")
        assertEquals(3, list.size)
        assertEquals("log-2", list[0].id)
        assertEquals("log-3", list[1].id)
        assertEquals("log-1", list[2].id)
        Unit
    }

    @Test
    fun al3_listByCategoryId_limit() = runBlocking {
        for (i in 1..5) {
            auditLogDb.insert(MaterialCategoryAuditLog(
                id = "log-$i", categoryId = "cat-1", userId = "user-1",
                action = "ACTION", createdAt = i.toLong() * 1000,
            ))
        }
        val list = auditLogDb.listByCategoryId("cat-1", limit = 3)
        assertEquals(3, list.size)
        Unit
    }

    @Test
    fun al4_listByUserId() = runBlocking {
        auditLogDb.insert(MaterialCategoryAuditLog(
            id = "log-1", categoryId = "cat-1", userId = "user-1",
            action = "CREATE", createdAt = now,
        ))
        auditLogDb.insert(MaterialCategoryAuditLog(
            id = "log-2", categoryId = "cat-2", userId = "user-2",
            action = "CREATE", createdAt = now,
        ))
        auditLogDb.insert(MaterialCategoryAuditLog(
            id = "log-3", categoryId = "cat-3", userId = "user-1",
            action = "UPDATE", createdAt = now + 1,
        ))
        val user1Logs = auditLogDb.listByUserId("user-1")
        assertEquals(2, user1Logs.size)
        assertTrue(user1Logs.all { it.userId == "user-1" })
        Unit
    }

    @Test
    fun al5_listByUserId_limit() = runBlocking {
        for (i in 1..5) {
            auditLogDb.insert(MaterialCategoryAuditLog(
                id = "log-$i", categoryId = "cat-$i", userId = "user-1",
                action = "ACTION", createdAt = i.toLong() * 1000,
            ))
        }
        val list = auditLogDb.listByUserId("user-1", limit = 2)
        assertEquals(2, list.size)
        Unit
    }

    @Test
    fun al6_different_categories_isolated() = runBlocking {
        auditLogDb.insert(MaterialCategoryAuditLog(
            id = "log-1", categoryId = "cat-1", userId = "user-1",
            action = "CREATE", createdAt = now,
        ))
        auditLogDb.insert(MaterialCategoryAuditLog(
            id = "log-2", categoryId = "cat-2", userId = "user-1",
            action = "CREATE", createdAt = now,
        ))
        assertEquals(1, auditLogDb.listByCategoryId("cat-1").size)
        assertEquals(1, auditLogDb.listByCategoryId("cat-2").size)
        assertTrue(auditLogDb.listByCategoryId("cat-3").isEmpty())
        Unit
    }
}
