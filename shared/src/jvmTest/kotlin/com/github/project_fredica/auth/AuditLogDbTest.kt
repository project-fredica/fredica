package com.github.project_fredica.auth

import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuditLogDbTest {
    private lateinit var db: Database
    private lateinit var auditLogDb: AuditLogDb
    private lateinit var tmpFile: File

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_audit_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        auditLogDb = AuditLogDb(db)
        runBlocking { auditLogDb.initialize() }
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    // A1: insert + query 往返
    @Test
    fun a1_insert_and_query() = runBlocking {
        val entry = AuditLogEntry(
            id = "log-1",
            timestamp = 1000L,
            eventType = "LOGIN_SUCCESS",
            actorUserId = "user-1",
            actorUsername = "alice",
            targetUserId = null,
            ipAddress = "127.0.0.1",
            userAgent = "TestAgent/1.0",
            details = "login from test",
        )
        auditLogDb.insert(entry)

        val result = auditLogDb.query()
        assertEquals(1, result.total)
        assertEquals(1, result.items.size)
        val found = result.items[0]
        assertEquals("log-1", found.id)
        assertEquals("LOGIN_SUCCESS", found.eventType)
        assertEquals("user-1", found.actorUserId)
        assertEquals("alice", found.actorUsername)
        assertEquals("127.0.0.1", found.ipAddress)
        assertEquals("TestAgent/1.0", found.userAgent)
        assertEquals("login from test", found.details)
        Unit
    }

    // A2: query filter by event_type
    @Test
    fun a2_query_filter_by_event_type() = runBlocking {
        auditLogDb.insert(AuditLogEntry(id = "log-1", timestamp = 1000L, eventType = "LOGIN_SUCCESS"))
        auditLogDb.insert(AuditLogEntry(id = "log-2", timestamp = 1001L, eventType = "LOGIN_FAILED"))
        auditLogDb.insert(AuditLogEntry(id = "log-3", timestamp = 1002L, eventType = "LOGIN_SUCCESS"))

        val successOnly = auditLogDb.query(eventType = "LOGIN_SUCCESS")
        assertEquals(2, successOnly.total)
        assertEquals(2, successOnly.items.size)
        assertTrue(successOnly.items.all { it.eventType == "LOGIN_SUCCESS" })

        val failedOnly = auditLogDb.query(eventType = "LOGIN_FAILED")
        assertEquals(1, failedOnly.total)
        assertEquals("log-2", failedOnly.items[0].id)
        Unit
    }

    // A3: query filter by actor_user_id
    @Test
    fun a3_query_filter_by_actor() = runBlocking {
        auditLogDb.insert(AuditLogEntry(id = "log-1", timestamp = 1000L, eventType = "LOGIN_SUCCESS", actorUserId = "user-1"))
        auditLogDb.insert(AuditLogEntry(id = "log-2", timestamp = 1001L, eventType = "LOGIN_SUCCESS", actorUserId = "user-2"))
        auditLogDb.insert(AuditLogEntry(id = "log-3", timestamp = 1002L, eventType = "LOGOUT", actorUserId = "user-1"))

        val user1Logs = auditLogDb.query(actorUserId = "user-1")
        assertEquals(2, user1Logs.total)
        assertTrue(user1Logs.items.all { it.actorUserId == "user-1" })
        Unit
    }

    // A4: query 按 timestamp DESC 排序
    @Test
    fun a4_query_ordered_by_timestamp_desc() = runBlocking {
        auditLogDb.insert(AuditLogEntry(id = "log-1", timestamp = 1000L, eventType = "A"))
        auditLogDb.insert(AuditLogEntry(id = "log-2", timestamp = 3000L, eventType = "B"))
        auditLogDb.insert(AuditLogEntry(id = "log-3", timestamp = 2000L, eventType = "C"))

        val result = auditLogDb.query()
        assertEquals(3, result.items.size)
        assertEquals("log-2", result.items[0].id) // 3000
        assertEquals("log-3", result.items[1].id) // 2000
        assertEquals("log-1", result.items[2].id) // 1000
        Unit
    }

    // A5: query limit + offset 分页
    @Test
    fun a5_query_pagination() = runBlocking {
        for (i in 1..10) {
            auditLogDb.insert(AuditLogEntry(id = "log-$i", timestamp = i.toLong() * 1000, eventType = "EVENT"))
        }

        val page1 = auditLogDb.query(limit = 3, offset = 0)
        assertEquals(10, page1.total)
        assertEquals(3, page1.items.size)
        // DESC: 10000, 9000, 8000
        assertEquals("log-10", page1.items[0].id)

        val page2 = auditLogDb.query(limit = 3, offset = 3)
        assertEquals(10, page2.total)
        assertEquals(3, page2.items.size)
        assertEquals("log-7", page2.items[0].id)
        Unit
    }

    // A6: deleteOlderThan
    @Test
    fun a6_deleteOlderThan() = runBlocking {
        auditLogDb.insert(AuditLogEntry(id = "log-old", timestamp = 1000L, eventType = "OLD"))
        auditLogDb.insert(AuditLogEntry(id = "log-new", timestamp = 5000L, eventType = "NEW"))

        val deleted = auditLogDb.deleteOlderThan(3000L)
        assertEquals(1, deleted)

        val result = auditLogDb.query()
        assertEquals(1, result.total)
        assertEquals("log-new", result.items[0].id)
        Unit
    }

    // A7: insert 自动生成 id 和 timestamp
    @Test
    fun a7_insert_auto_id_and_timestamp() = runBlocking {
        auditLogDb.insert(AuditLogEntry(id = "", timestamp = 0L, eventType = "AUTO_TEST"))

        val result = auditLogDb.query()
        assertEquals(1, result.total)
        val entry = result.items[0]
        assertTrue(entry.id.isNotBlank())
        assertTrue(entry.timestamp > 0)
        assertEquals("AUTO_TEST", entry.eventType)
        Unit
    }

    // A8: 组合过滤 event_type + actor_user_id
    @Test
    fun a8_query_combined_filters() = runBlocking {
        auditLogDb.insert(AuditLogEntry(id = "1", timestamp = 1000L, eventType = "LOGIN_SUCCESS", actorUserId = "u1"))
        auditLogDb.insert(AuditLogEntry(id = "2", timestamp = 1001L, eventType = "LOGIN_FAILED", actorUserId = "u1"))
        auditLogDb.insert(AuditLogEntry(id = "3", timestamp = 1002L, eventType = "LOGIN_SUCCESS", actorUserId = "u2"))

        val result = auditLogDb.query(eventType = "LOGIN_SUCCESS", actorUserId = "u1")
        assertEquals(1, result.total)
        assertEquals("1", result.items[0].id)
        Unit
    }
}
