package com.github.project_fredica.auth

import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class TenantInviteLinkDbTest {
    private lateinit var db: Database
    private lateinit var linkDb: TenantInviteLinkDb
    private lateinit var regDb: TenantInviteRegistrationDb
    private lateinit var userDb: UserDb
    private lateinit var tmpFile: File

    private val futureExpiry: String get() = Instant.now().plus(7, ChronoUnit.DAYS).toString()
    private val pastExpiry: String get() = Instant.now().minus(1, ChronoUnit.DAYS).toString()

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_tenant_invite_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        linkDb = TenantInviteLinkDb(db)
        regDb = TenantInviteRegistrationDb(db)
        userDb = UserDb(db)
        runBlocking {
            userDb.initialize()
            linkDb.initialize()
            regDb.initialize()
        }
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    // A-1.1: create → findById 返回一致数据
    @Test
    fun a1_1_create_and_findById() = runBlocking {
        val id = linkDb.create(
            pathId = "tenant-link",
            label = "租户链接",
            maxUses = 10,
            expiresAt = futureExpiry,
            createdBy = "admin-1",
        )
        assertNotNull(id)

        val found = linkDb.findById(id)
        assertNotNull(found)
        assertEquals(id, found.id)
        assertEquals("tenant-link", found.pathId)
        assertEquals("租户链接", found.label)
        assertEquals("active", found.status)
        assertEquals(10, found.maxUses)
        assertEquals("admin-1", found.createdBy)
        assertEquals(0, found.usedCount)
        Unit
    }

    // A-1.2: create → findByPathId
    @Test
    fun a1_2_create_and_findByPathId() = runBlocking {
        val id = linkDb.create(
            pathId = "path-xyz",
            label = "",
            maxUses = 5,
            expiresAt = futureExpiry,
            createdBy = "admin-1",
        )
        val found = linkDb.findByPathId("path-xyz")
        assertNotNull(found)
        assertEquals(id, found.id)
        Unit
    }

    // A-1.3: listAll 返回含 used_count 聚合
    @Test
    fun a1_3_listAll_with_used_count() = runBlocking {
        val id1 = linkDb.create("link-111", "A", 10, futureExpiry, "admin-1")
        val id2 = linkDb.create("link-222", "B", 10, futureExpiry, "admin-1")

        // 创建用户用于注册记录
        val userId = userDb.createUser("testuser1", "Test User", "hash1")
        regDb.record(id1, userId, "1.1.1.1", "UA")

        val all = linkDb.listAll()
        assertEquals(2, all.size)

        val link1 = all.find { it.id == id1 }
        assertNotNull(link1)
        assertEquals(1, link1.usedCount)

        val link2 = all.find { it.id == id2 }
        assertNotNull(link2)
        assertEquals(0, link2.usedCount)
        Unit
    }

    // A-1.4: updateStatus
    @Test
    fun a1_4_updateStatus() = runBlocking {
        val id = linkDb.create("status-t", "", 5, futureExpiry, "admin-1")
        linkDb.updateStatus(id, "disabled")
        val found = linkDb.findById(id)
        assertNotNull(found)
        assertEquals("disabled", found.status)
        Unit
    }

    // A-1.5: updateLabel
    @Test
    fun a1_5_updateLabel() = runBlocking {
        val id = linkDb.create("label-t", "旧标签", 5, futureExpiry, "admin-1")
        linkDb.updateLabel(id, "新标签")
        val found = linkDb.findById(id)
        assertNotNull(found)
        assertEquals("新标签", found.label)
        Unit
    }

    // A-1.6: delete 无注册记录
    @Test
    fun a1_6_delete_no_registrations() = runBlocking {
        val id = linkDb.create("del-ok", "", 5, futureExpiry, "admin-1")
        linkDb.delete(id)
        assertNull(linkDb.findById(id))
        Unit
    }

    // ==================== A-2: 可用性判定 ====================

    // A-2.1: active + 未过期 + 0/5 已用 → isUsable = true
    @Test
    fun a2_1_isUsable_active_not_expired_not_full() = runBlocking {
        linkDb.create("usable-1", "", 5, futureExpiry, "admin-1")
        assertTrue(linkDb.isUsable("usable-1"))
        Unit
    }

    // A-2.2: active + 未过期 + 5/5 已用 → isUsable = false
    @Test
    fun a2_2_isUsable_full() = runBlocking {
        val id = linkDb.create("usable-2", "", 5, futureExpiry, "admin-1")
        repeat(5) { i ->
            val userId = userDb.createUser("user-full-$i", "User $i", "hash")
            regDb.record(id, userId, "1.1.1.1", "UA")
        }
        assertFalse(linkDb.isUsable("usable-2"))
        Unit
    }

    // A-2.3: active + 已过期 + 0/5 已用 → isUsable = false
    @Test
    fun a2_3_isUsable_expired() = runBlocking {
        // 直接插入一条已过期的记录（绕过 create 的 require 检查）
        insertLinkDirectly("usable-3", "active", 5, pastExpiry)
        assertFalse(linkDb.isUsable("usable-3"))
        Unit
    }

    // A-2.4: disabled + 未过期 + 0/5 已用 → isUsable = false
    @Test
    fun a2_4_isUsable_disabled() = runBlocking {
        linkDb.create("usable-4", "", 5, futureExpiry, "admin-1")
        linkDb.updateStatus(linkDb.findByPathId("usable-4")!!.id, "disabled")
        assertFalse(linkDb.isUsable("usable-4"))
        Unit
    }

    // A-2.5: active + 未过期 + 4/5 已用 → isUsable = true
    @Test
    fun a2_5_isUsable_one_slot_left() = runBlocking {
        val id = linkDb.create("usable-5", "", 5, futureExpiry, "admin-1")
        repeat(4) { i ->
            val userId = userDb.createUser("user-slot-$i", "User $i", "hash")
            regDb.record(id, userId, "1.1.1.1", "UA")
        }
        assertTrue(linkDb.isUsable("usable-5"))
        Unit
    }

    // A-2.6: 过期时间恰好等于 now → isUsable = false
    @Test
    fun a2_6_isUsable_expires_at_now() = runBlocking {
        // 设置 expires_at 为 1 秒前（确保 now >= expiry）
        val justPast = Instant.now().minus(1, ChronoUnit.SECONDS).toString()
        insertLinkDirectly("usable-6", "active", 5, justPast)
        assertFalse(linkDb.isUsable("usable-6"))
        Unit
    }

    // ==================== A-3: 删除保护 ====================

    // A-3.1: 无注册记录 → delete 成功
    @Test
    fun a3_1_delete_no_registrations() = runBlocking {
        val id = linkDb.create("del-prot1", "", 5, futureExpiry, "admin-1")
        linkDb.delete(id)
        assertNull(linkDb.findById(id))
        Unit
    }

    // A-3.2: 有注册记录 → delete 抛异常
    @Test
    fun a3_2_delete_with_registrations_throws() = runBlocking {
        val id = linkDb.create("del-prot2", "", 5, futureExpiry, "admin-1")
        val userId = userDb.createUser("del-user", "Del User", "hash")
        regDb.record(id, userId, "1.1.1.1", "UA")

        val ex = assertFailsWith<IllegalStateException> {
            linkDb.delete(id)
        }
        assertTrue(ex.message!!.contains("注册记录"))

        // 记录仍存在
        assertNotNull(linkDb.findById(id))
        Unit
    }

    // A-3.3: 有注册记录 → updateStatus("disabled") 成功
    @Test
    fun a3_3_disable_with_registrations_ok() = runBlocking {
        val id = linkDb.create("del-prot3", "", 5, futureExpiry, "admin-1")
        val userId = userDb.createUser("dis-user", "Dis User", "hash")
        regDb.record(id, userId, "1.1.1.1", "UA")

        linkDb.updateStatus(id, "disabled")
        val found = linkDb.findById(id)
        assertNotNull(found)
        assertEquals("disabled", found.status)
        Unit
    }

    // ==================== A-4: 安全测试 ====================

    // A-4.1: pathId 含 SQL 注入
    @Test
    fun a4_1_sql_injection_in_pathId() = runBlocking {
        assertFailsWith<Exception> {
            linkDb.create("'; DROP TABLE tenant_invite_link; --", "", 5, futureExpiry, "admin-1")
        }
        // 表仍然存在
        assertNotNull(linkDb.listAll())
        Unit
    }

    // A-4.4: pathId 为空字符串
    @Test
    fun a4_4_empty_pathId() = runBlocking {
        assertFailsWith<Exception> {
            linkDb.create("", "", 5, futureExpiry, "admin-1")
        }
        Unit
    }

    // A-4.5: pathId 超长
    @Test
    fun a4_5_overlong_pathId() = runBlocking {
        assertFailsWith<Exception> {
            linkDb.create("a".repeat(1000), "", 5, futureExpiry, "admin-1")
        }
        Unit
    }

    // A-4.7: 重复 pathId
    @Test
    fun a4_7_duplicate_pathId() = runBlocking {
        linkDb.create("dup-tenant", "", 5, futureExpiry, "admin-1")
        assertFailsWith<Exception> {
            linkDb.create("dup-tenant", "", 5, futureExpiry, "admin-1")
        }
        Unit
    }

    // A-4.8: max_uses = 0
    @Test
    fun a4_8_max_uses_zero() = runBlocking {
        assertFailsWith<Exception> {
            linkDb.create("zero-uses", "", 0, futureExpiry, "admin-1")
        }
        Unit
    }

    // A-4.9: max_uses = -1
    @Test
    fun a4_9_max_uses_negative() = runBlocking {
        assertFailsWith<Exception> {
            linkDb.create("neg-uses", "", -1, futureExpiry, "admin-1")
        }
        Unit
    }

    // A-4.10: max_uses = Integer.MAX_VALUE
    @Test
    fun a4_10_max_uses_max_int() = runBlocking {
        val id = linkDb.create("max-uses", "", Int.MAX_VALUE, futureExpiry, "admin-1")
        val found = linkDb.findById(id)
        assertNotNull(found)
        assertEquals(Int.MAX_VALUE, found.maxUses)
        Unit
    }

    // A-4.11: expires_at = 过去时间
    @Test
    fun a4_11_expires_at_past() = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            linkDb.create("past-exp", "", 5, pastExpiry, "admin-1")
        }
        Unit
    }

    // A-4.12: expires_at = 非法格式
    @Test
    fun a4_12_expires_at_invalid_format() = runBlocking {
        assertFailsWith<Exception> {
            linkDb.create("bad-date", "", 5, "not-a-date", "admin-1")
        }
        Unit
    }

    // Registration: listByLinkId 含联查字段
    @Test
    fun registration_listByLinkId_with_user_info() = runBlocking {
        val linkId = linkDb.create("reg-list", "", 10, futureExpiry, "admin-1")
        val userId = userDb.createUser("reguser", "Reg User", "hash")
        regDb.record(linkId, userId, "1.1.1.1", "Chrome")

        val regs = regDb.listByLinkId(linkId)
        assertEquals(1, regs.size)
        assertEquals(userId, regs[0].userId)
        assertEquals("reguser", regs[0].username)
        assertEquals("Reg User", regs[0].displayName)
        assertEquals("1.1.1.1", regs[0].ipAddress)
        assertEquals("Chrome", regs[0].userAgent)
        Unit
    }

    // Registration: countByLinkId
    @Test
    fun registration_countByLinkId() = runBlocking {
        val linkId = linkDb.create("reg-count", "", 10, futureExpiry, "admin-1")
        assertEquals(0, regDb.countByLinkId(linkId))

        val userId1 = userDb.createUser("cnt-user1", "U1", "hash")
        val userId2 = userDb.createUser("cnt-user2", "U2", "hash")
        regDb.record(linkId, userId1, "", "")
        regDb.record(linkId, userId2, "", "")
        assertEquals(2, regDb.countByLinkId(linkId))
        Unit
    }

    // updateStatus 非法值
    @Test
    fun updateStatus_invalid_value() = runBlocking {
        val id = linkDb.create("inv-stat", "", 5, futureExpiry, "admin-1")
        assertFailsWith<IllegalArgumentException> {
            linkDb.updateStatus(id, "root")
        }
        Unit
    }

    // findById 不存在
    @Test
    fun findById_not_found() = runBlocking {
        assertNull(linkDb.findById("nonexistent"))
        Unit
    }

    // isUsable 不存在的 pathId
    @Test
    fun isUsable_nonexistent_pathId() = runBlocking {
        assertFalse(linkDb.isUsable("no-such-path"))
        Unit
    }

    // pathId 最短合法值（3 字符）
    @Test
    fun pathId_minimum_length() = runBlocking {
        val id = linkDb.create("xyz", "", 5, futureExpiry, "admin-1")
        assertNotNull(id)
        Unit
    }

    // pathId 2 字符应被拒绝
    @Test
    fun pathId_too_short() = runBlocking {
        assertFailsWith<Exception> {
            linkDb.create("ab", "", 5, futureExpiry, "admin-1")
        }
        Unit
    }

    // ==================== 辅助方法 ====================

    /** 直接插入一条链接记录，绕过 create 的 require 检查（用于测试已过期场景） */
    private suspend fun insertLinkDirectly(pathId: String, status: String, maxUses: Int, expiresAt: String) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO tenant_invite_link (id, path_id, label, status, max_uses, expires_at, created_by, created_at, updated_at)
                    VALUES (?, ?, '', ?, ?, ?, 'admin-1', ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    val id = java.util.UUID.randomUUID().toString()
                    val now = java.time.Instant.now().toString()
                    ps.setString(1, id)
                    ps.setString(2, pathId)
                    ps.setString(3, status)
                    ps.setInt(4, maxUses)
                    ps.setString(5, expiresAt)
                    ps.setString(6, now)
                    ps.setString(7, now)
                    ps.executeUpdate()
                }
            }
        }
    }
}
