package com.github.project_fredica.auth

import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.api.routes.UserChangePasswordRoute
import com.github.project_fredica.api.routes.UserUpdateDisplayNameRoute
import com.github.project_fredica.apputil.loadJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.ktorm.database.Database
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UserChangePasswordRoute / UserUpdateDisplayNameRoute handler 测试。
 * 需要真实 UserDb + AuditLogDb + mock AuthServiceApi。
 */
class UserSelfServiceRouteTest {
    private lateinit var db: Database
    private lateinit var userDb: UserDb
    private lateinit var auditLogDb: AuditLogDb
    private lateinit var tmpFile: File

    // 可控的 changePassword 结果
    private var changePasswordResult: ChangePasswordResult = ChangePasswordResult(success = true)

    private val mockAuthService = object : AuthServiceApi {
        override suspend fun resolveIdentity(authHeader: String?): AuthIdentity? = null
        override suspend fun login(username: String, password: String, userAgent: String, ipAddress: String) =
            LoginResult(error = "not implemented")
        override suspend fun logout(sessionId: String) {}
        override suspend fun initializeInstance(username: String, password: String) =
            InitResult(error = "not implemented")
        override suspend fun isInstanceInitialized() = true
        override suspend fun createUser(username: String, displayName: String, password: String) =
            CreateUserResult(error = "not implemented")
        override suspend fun changePassword(userId: String, oldPassword: String, newPassword: String, currentSessionId: String): ChangePasswordResult =
            changePasswordResult
    }

    private val tenantUser = AuthIdentity.TenantUser(
        userId = "u-tenant",
        username = "alice",
        displayName = "Alice",
        permissions = "read",
        sessionId = "sess-tenant",
    )

    private val rootUser = AuthIdentity.RootUser(
        userId = "u-root",
        username = "admin",
        displayName = "Admin",
        permissions = "*",
        sessionId = "sess-root",
    )

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_self_service_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        userDb = UserDb(db)
        auditLogDb = AuditLogDb(db)
        runBlocking {
            userDb.initialize()
            auditLogDb.initialize()
        }
        UserService.initialize(userDb)
        AuditLogService.initialize(auditLogDb)
        AuthServiceHolder.initialize(mockAuthService)
        changePasswordResult = ChangePasswordResult(success = true)
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    private fun ctx(identity: AuthIdentity) = RouteContext(identity = identity, clientIp = null, userAgent = null)

    private suspend fun callWithIdentity(identity: AuthIdentity, handler: suspend (String, RouteContext) -> com.github.project_fredica.apputil.ValidJsonString, param: String = ""): JsonObject {
        val result = handler(param, ctx(identity))
        return result.str.loadJson().getOrThrow() as JsonObject
    }

    // ==================== UserChangePasswordRoute ====================

    // US1: 修改密码成功
    @Test
    fun us1_change_password_success() = runBlocking {
        changePasswordResult = ChangePasswordResult(success = true)
        val resp = callWithIdentity(tenantUser, UserChangePasswordRoute::handler, """{"old_password":"oldpass123","new_password":"newpass123"}""")
        assertTrue(resp["success"]!!.jsonPrimitive.boolean)
        Unit
    }

    // US2: Guest 身份修改密码 → 错误
    @Test
    fun us2_guest_change_password() = runBlocking {
        val resp = callWithIdentity(AuthIdentity.Guest, UserChangePasswordRoute::handler, """{"old_password":"oldpass123","new_password":"newpass123"}""")
        assertEquals("Guest 身份无法修改密码", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // US3: 新密码太短（<8）
    @Test
    fun us3_new_password_too_short() = runBlocking {
        val resp = callWithIdentity(tenantUser, UserChangePasswordRoute::handler, """{"old_password":"oldpass123","new_password":"short"}""")
        assertTrue(resp["error"]!!.jsonPrimitive.content.contains("新密码长度需 8-128"))
        Unit
    }

    // US4: 请求参数无效
    @Test
    fun us4_invalid_param() = runBlocking {
        val resp = callWithIdentity(tenantUser, UserChangePasswordRoute::handler, "not-json")
        assertEquals("请求参数无效", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // US5: 审计日志 — 修改密码成功
    @Test
    fun us5_audit_log_password_changed() = runBlocking {
        changePasswordResult = ChangePasswordResult(success = true)
        callWithIdentity(tenantUser, UserChangePasswordRoute::handler, """{"old_password":"oldpass123","new_password":"newpass123"}""")
        val logs = auditLogDb.query(eventType = "PASSWORD_CHANGED")
        assertEquals(1, logs.total)
        assertEquals("u-tenant", logs.items[0].actorUserId)
        assertEquals("alice", logs.items[0].actorUsername)
        Unit
    }

    // US5b: changePassword 失败时不写审计日志
    @Test
    fun us5b_no_audit_log_on_failure() = runBlocking {
        changePasswordResult = ChangePasswordResult(error = "旧密码错误")
        callWithIdentity(tenantUser, UserChangePasswordRoute::handler, """{"old_password":"wrongold","new_password":"newpass123"}""")
        val logs = auditLogDb.query(eventType = "PASSWORD_CHANGED")
        assertEquals(0, logs.total)
        Unit
    }

    // ==================== UserUpdateDisplayNameRoute ====================

    // US6: 修改自己的显示名
    @Test
    fun us6_update_own_display_name() = runBlocking {
        val userId = userDb.createUser("alice", "Alice", "hash123")
        val identity = AuthIdentity.TenantUser(
            userId = userId, username = "alice", displayName = "Alice",
            permissions = "read", sessionId = "sess-alice",
        )
        val resp = callWithIdentity(identity, UserUpdateDisplayNameRoute::handler, """{"display_name":"NewAlice"}""")
        assertTrue(resp["success"]!!.jsonPrimitive.boolean)
        assertEquals("NewAlice", resp["display_name"]!!.jsonPrimitive.content)
        // 验证 DB 中已更新
        val user = userDb.findById(userId)!!
        assertEquals("NewAlice", user.displayName)
        Unit
    }

    // US7: Guest 身份更新显示名 → 错误
    @Test
    fun us7_guest_update_display_name() = runBlocking {
        val resp = callWithIdentity(AuthIdentity.Guest, UserUpdateDisplayNameRoute::handler, """{"display_name":"NewName"}""")
        assertEquals("Guest 身份无法更新显示名", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // US8: 非 ROOT 修改他人显示名 → 权限不足
    @Test
    fun us8_non_root_update_other() = runBlocking {
        val resp = callWithIdentity(tenantUser, UserUpdateDisplayNameRoute::handler, """{"user_id":"other-user-id","display_name":"Hacked"}""")
        assertTrue(resp["error"]!!.jsonPrimitive.content.contains("权限不足"))
        Unit
    }

    // US9: ROOT 修改他人显示名 → 成功
    @Test
    fun us9_root_update_other() = runBlocking {
        val targetId = userDb.createUser("bob", "Bob", "hash123")
        val resp = callWithIdentity(rootUser, UserUpdateDisplayNameRoute::handler, """{"user_id":"$targetId","display_name":"BobNew"}""")
        assertTrue(resp["success"]!!.jsonPrimitive.boolean)
        assertEquals("BobNew", resp["display_name"]!!.jsonPrimitive.content)
        val user = userDb.findById(targetId)!!
        assertEquals("BobNew", user.displayName)
        Unit
    }

    // US10: 显示名超长（>64）
    @Test
    fun us10_display_name_too_long() = runBlocking {
        val longName = "a".repeat(65)
        val resp = callWithIdentity(tenantUser, UserUpdateDisplayNameRoute::handler, """{"display_name":"$longName"}""")
        assertTrue(resp["error"]!!.jsonPrimitive.content.contains("显示名长度需 1-64"))
        Unit
    }

    // US11: 显示名为空
    @Test
    fun us11_display_name_empty() = runBlocking {
        val resp = callWithIdentity(tenantUser, UserUpdateDisplayNameRoute::handler, """{"display_name":""}""")
        assertTrue(resp["error"]!!.jsonPrimitive.content.contains("显示名长度需 1-64"))
        Unit
    }

    // US12: 用户不存在
    @Test
    fun us12_user_not_found() = runBlocking {
        // tenantUser.userId = "u-tenant" 但 DB 中没有这个用户
        val resp = callWithIdentity(tenantUser, UserUpdateDisplayNameRoute::handler, """{"display_name":"NewName"}""")
        assertEquals("用户不存在", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // US13: 审计日志 — 更新显示名
    @Test
    fun us13_audit_log_display_name_updated() = runBlocking {
        val userId = userDb.createUser("alice", "Alice", "hash123")
        val identity = AuthIdentity.TenantUser(
            userId = userId, username = "alice", displayName = "Alice",
            permissions = "read", sessionId = "sess-alice",
        )
        callWithIdentity(identity, UserUpdateDisplayNameRoute::handler, """{"display_name":"AliceNew"}""")
        val logs = auditLogDb.query(eventType = "USER_DISPLAY_NAME_UPDATED")
        assertEquals(1, logs.total)
        assertEquals(userId, logs.items[0].actorUserId)
        assertEquals("alice", logs.items[0].actorUsername)
        assertEquals(userId, logs.items[0].targetUserId)
        assertTrue(logs.items[0].details!!.contains("Alice -> AliceNew"))
        Unit
    }
}
