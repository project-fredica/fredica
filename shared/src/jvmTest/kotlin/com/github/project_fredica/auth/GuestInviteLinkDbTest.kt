package com.github.project_fredica.auth

import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class GuestInviteLinkDbTest {
    private lateinit var db: Database
    private lateinit var linkDb: GuestInviteLinkDb
    private lateinit var visitDb: GuestInviteVisitDb
    private lateinit var tmpFile: File

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_guest_invite_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        linkDb = GuestInviteLinkDb(db)
        visitDb = GuestInviteVisitDb(db)
        runBlocking {
            linkDb.initialize()
            visitDb.initialize()
        }
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    // A-1.1: create → findById 返回一致数据
    @Test
    fun a1_1_create_and_findById() = runBlocking {
        val id = linkDb.create(pathId = "test-link", label = "测试链接", createdBy = "user-1")
        assertNotNull(id)

        val found = linkDb.findById(id)
        assertNotNull(found)
        assertEquals(id, found.id)
        assertEquals("test-link", found.pathId)
        assertEquals("测试链接", found.label)
        assertEquals("active", found.status)
        assertEquals("user-1", found.createdBy)
        assertEquals(0, found.visitCount)
        assertNotNull(found.createdAt)
        assertNotNull(found.updatedAt)
        Unit
    }

    // A-1.2: create → findByPathId 返回一致数据
    @Test
    fun a1_2_create_and_findByPathId() = runBlocking {
        val id = linkDb.create(pathId = "path-abc", label = "标签", createdBy = "user-1")
        val found = linkDb.findByPathId("path-abc")
        assertNotNull(found)
        assertEquals(id, found.id)
        assertEquals("path-abc", found.pathId)
        Unit
    }

    // A-1.3: listAll 返回含 visit_count 聚合
    @Test
    fun a1_3_listAll_with_visit_count() = runBlocking {
        val id1 = linkDb.create(pathId = "link-aaa", label = "A", createdBy = "user-1")
        val id2 = linkDb.create(pathId = "link-bbb", label = "B", createdBy = "user-1")

        // 给 link-aaa 添加 3 条访问记录
        visitDb.record(id1, "1.2.3.4", "Mozilla/5.0")
        visitDb.record(id1, "5.6.7.8", "Chrome")
        visitDb.record(id1, "9.0.1.2", "Safari")

        val all = linkDb.listAll()
        assertEquals(2, all.size)

        val linkA = all.find { it.id == id1 }
        assertNotNull(linkA)
        assertEquals(3, linkA.visitCount)

        val linkB = all.find { it.id == id2 }
        assertNotNull(linkB)
        assertEquals(0, linkB.visitCount)
        Unit
    }

    // A-1.4: updateStatus → findById 状态已变
    @Test
    fun a1_4_updateStatus() = runBlocking {
        val id = linkDb.create(pathId = "status-test", label = "", createdBy = "user-1")
        linkDb.updateStatus(id, "disabled")
        val found = linkDb.findById(id)
        assertNotNull(found)
        assertEquals("disabled", found.status)
        Unit
    }

    // A-1.5: updateLabel → findById 标签已变
    @Test
    fun a1_5_updateLabel() = runBlocking {
        val id = linkDb.create(pathId = "label-test", label = "旧标签", createdBy = "user-1")
        linkDb.updateLabel(id, "新标签")
        val found = linkDb.findById(id)
        assertNotNull(found)
        assertEquals("新标签", found.label)
        Unit
    }

    // A-1.6: delete → findById 返回 null
    @Test
    fun a1_6_delete() = runBlocking {
        val id = linkDb.create(pathId = "del-test", label = "", createdBy = "user-1")
        linkDb.delete(id)
        val found = linkDb.findById(id)
        assertNull(found)
        Unit
    }

    // A-1.7: 游客链接 delete 同时删除关联 guest_invite_visit 记录
    @Test
    fun a1_7_delete_cascades_visits() = runBlocking {
        val id = linkDb.create(pathId = "cascade-del", label = "", createdBy = "user-1")
        visitDb.record(id, "1.1.1.1", "UA1")
        visitDb.record(id, "2.2.2.2", "UA2")
        assertEquals(2, visitDb.countByLinkId(id))

        linkDb.delete(id)
        assertEquals(0, visitDb.countByLinkId(id))
        Unit
    }

    // A-4.1: pathId 含 SQL 注入 payload
    @Test
    fun a4_1_sql_injection_in_pathId() = runBlocking {
        // pathId 含 SQL 注入字符，但 CHECK 约束要求 [a-zA-Z0-9_-]，应被拒绝
        val ex = assertFailsWith<Exception> {
            linkDb.create(pathId = "'; DROP TABLE guest_invite_link; --", label = "", createdBy = "user-1")
        }
        // 表仍然存在
        val all = linkDb.listAll()
        assertNotNull(all) // 表未被删除
        Unit
    }

    // A-4.2: pathId 含路径穿越
    @Test
    fun a4_2_path_traversal_in_pathId() = runBlocking {
        // 含 / 和 . 的 pathId 不匹配 GLOB 约束
        assertFailsWith<Exception> {
            linkDb.create(pathId = "../../etc/passwd", label = "", createdBy = "user-1")
        }
        Unit
    }

    // A-4.3: label 含 XSS payload（DB 层正常存储）
    @Test
    fun a4_3_xss_in_label() = runBlocking {
        val id = linkDb.create(pathId = "xss-test", label = "<script>alert(1)</script>", createdBy = "user-1")
        val found = linkDb.findById(id)
        assertNotNull(found)
        assertEquals("<script>alert(1)</script>", found.label)
        Unit
    }

    // A-4.4: pathId 为空字符串
    @Test
    fun a4_4_empty_pathId() = runBlocking {
        assertFailsWith<Exception> {
            linkDb.create(pathId = "", label = "", createdBy = "user-1")
        }
        Unit
    }

    // A-4.5: pathId 超长（1000 字符）
    @Test
    fun a4_5_overlong_pathId() = runBlocking {
        val longPathId = "a".repeat(1000)
        assertFailsWith<Exception> {
            linkDb.create(pathId = longPathId, label = "", createdBy = "user-1")
        }
        Unit
    }

    // A-4.6: pathId 含 Unicode 零宽字符
    @Test
    fun a4_6_unicode_zero_width_pathId() = runBlocking {
        assertFailsWith<Exception> {
            linkDb.create(pathId = "abc\u200Bdef", label = "", createdBy = "user-1")
        }
        Unit
    }

    // A-4.7: 重复 pathId
    @Test
    fun a4_7_duplicate_pathId() = runBlocking {
        linkDb.create(pathId = "dup-test", label = "first", createdBy = "user-1")
        assertFailsWith<Exception> {
            linkDb.create(pathId = "dup-test", label = "second", createdBy = "user-1")
        }
        Unit
    }

    // A-4.13: 批量 record 同一 linkId
    @Test
    fun a4_13_batch_visit_records() = runBlocking {
        val linkId = linkDb.create(pathId = "conc-test", label = "", createdBy = "user-1")
        repeat(100) { i ->
            visitDb.record(linkId, "10.0.0.$i", "UA-$i")
        }
        assertEquals(100, visitDb.countByLinkId(linkId))
        Unit
    }

    // Visit: listByLinkId 分页
    @Test
    fun visit_listByLinkId_pagination() = runBlocking {
        val linkId = linkDb.create(pathId = "page-test", label = "", createdBy = "user-1")
        repeat(5) { visitDb.record(linkId, "1.1.1.1", "UA") }

        val page1 = visitDb.listByLinkId(linkId, limit = 3, offset = 0)
        assertEquals(3, page1.size)

        val page2 = visitDb.listByLinkId(linkId, limit = 3, offset = 3)
        assertEquals(2, page2.size)
        Unit
    }

    // Visit: countByLinkId
    @Test
    fun visit_countByLinkId() = runBlocking {
        val linkId = linkDb.create(pathId = "cnt-test", label = "", createdBy = "user-1")
        assertEquals(0, visitDb.countByLinkId(linkId))
        visitDb.record(linkId, "1.1.1.1", "UA")
        visitDb.record(linkId, "2.2.2.2", "UA")
        assertEquals(2, visitDb.countByLinkId(linkId))
        Unit
    }

    // updateStatus 非法值
    @Test
    fun updateStatus_invalid_value() = runBlocking {
        val id = linkDb.create(pathId = "inv-status", label = "", createdBy = "user-1")
        assertFailsWith<IllegalArgumentException> {
            linkDb.updateStatus(id, "root")
        }
        Unit
    }

    // findById 不存在
    @Test
    fun findById_not_found() = runBlocking {
        val found = linkDb.findById("nonexistent-id")
        assertNull(found)
        Unit
    }

    // findByPathId 不存在
    @Test
    fun findByPathId_not_found() = runBlocking {
        val found = linkDb.findByPathId("nonexistent-path")
        assertNull(found)
        Unit
    }

    // pathId 最短合法值（3 字符）
    @Test
    fun pathId_minimum_length() = runBlocking {
        val id = linkDb.create(pathId = "abc", label = "", createdBy = "user-1")
        assertNotNull(id)
        val found = linkDb.findByPathId("abc")
        assertNotNull(found)
        Unit
    }

    // pathId 2 字符应被拒绝
    @Test
    fun pathId_too_short() = runBlocking {
        assertFailsWith<Exception> {
            linkDb.create(pathId = "ab", label = "", createdBy = "user-1")
        }
        Unit
    }
}
