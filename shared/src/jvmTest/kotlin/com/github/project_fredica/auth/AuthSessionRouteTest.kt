package com.github.project_fredica.auth

import com.github.project_fredica.api.routes.AuthGuestValidateRoute
import com.github.project_fredica.api.routes.AuthLogoutRoute
import com.github.project_fredica.api.routes.AuthMeRoute
import com.github.project_fredica.api.routes.RouteContext
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
 * AuthLogoutRoute / AuthMeRoute / AuthGuestValidateRoute handler 测试。
 */
class AuthSessionRouteTest {
    private lateinit var db: Database
    private lateinit var auditLogDb: AuditLogDb
    private lateinit var tmpFile: File

    private var logoutCalledWith: String? = null

    private val mockAuthService = object : AuthServiceApi {
        override suspend fun resolveIdentity(authHeader: String?): AuthIdentity? = null
        override suspend fun login(username: String, password: String, userAgent: String, ipAddress: String) =
            LoginResult(error = "not implemented")
        override suspend fun logout(sessionId: String) {
            logoutCalledWith = sessionId
        }
        override suspend fun initializeInstance(username: String, password: String) =
            InitResult(error = "not implemented")
        override suspend fun isInstanceInitialized() = true
        override suspend fun createUser(username: String, displayName: String, password: String) =
            CreateUserResult(error = "not implemented")
        override suspend fun changePassword(userId: String, oldPassword: String, newPassword: String, currentSessionId: String) =
            ChangePasswordResult(error = "not implemented")
    }

    private val tenantUser = AuthIdentity.TenantUser(
        userId = "u-1",
        username = "alice",
        displayName = "Alice",
        permissions = "read,write",
        sessionId = "sess-1",
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
        tmpFile = File.createTempFile("test_session_route_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        auditLogDb = AuditLogDb(db)
        runBlocking { auditLogDb.initialize() }
        AuditLogService.initialize(auditLogDb)
        AuthServiceHolder.initialize(mockAuthService)
        logoutCalledWith = null
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    private fun ctx(identity: AuthIdentity? = null) = RouteContext(identity = identity, clientIp = null, userAgent = null)

    private suspend fun callWithIdentity(identity: AuthIdentity, handler: suspend (String, RouteContext) -> com.github.project_fredica.apputil.ValidJsonString, param: String = ""): JsonObject {
        val result = handler(param, ctx(identity))
        return result.str.loadJson().getOrThrow() as JsonObject
    }

    private suspend fun callWithoutIdentity(handler: suspend (String, RouteContext) -> com.github.project_fredica.apputil.ValidJsonString, param: String = ""): JsonObject {
        val result = handler(param, ctx())
        return result.str.loadJson().getOrThrow() as JsonObject
    }

    // ==================== AuthLogoutRoute ====================

    // S1: Authenticated 用户登出 → success + logout 被调用
    @Test
    fun s1_authenticated_user_logout() = runBlocking {
        val resp = callWithIdentity(tenantUser, AuthLogoutRoute::handler)
        assertTrue(resp["success"]!!.jsonPrimitive.boolean)
        assertEquals("sess-1", logoutCalledWith)
        Unit
    }

    // S2: Guest 身份登出 → error
    @Test
    fun s2_guest_logout_returns_error() = runBlocking {
        val resp = callWithIdentity(AuthIdentity.Guest, AuthLogoutRoute::handler)
        assertEquals("Guest 身份无法登出", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // S1b: 登出后审计日志写入 LOGOUT
    @Test
    fun s1b_logout_writes_audit_log() = runBlocking {
        callWithIdentity(tenantUser, AuthLogoutRoute::handler)
        val logs = auditLogDb.query(eventType = "LOGOUT")
        assertEquals(1, logs.total)
        assertEquals("u-1", logs.items[0].actorUserId)
        assertEquals("alice", logs.items[0].actorUsername)
        Unit
    }

    // ==================== AuthMeRoute ====================

    // S3: TenantUser 查询自身 → 返回完整用户信息
    @Test
    fun s3_tenant_user_me() = runBlocking {
        val resp = callWithIdentity(tenantUser, AuthMeRoute::handler)
        assertEquals("u-1", resp["user_id"]!!.jsonPrimitive.content)
        assertEquals("alice", resp["username"]!!.jsonPrimitive.content)
        assertEquals("Alice", resp["display_name"]!!.jsonPrimitive.content)
        assertEquals("tenant", resp["role"]!!.jsonPrimitive.content)
        assertEquals("read,write", resp["permissions"]!!.jsonPrimitive.content)
        assertEquals("sess-1", resp["session_id"]!!.jsonPrimitive.content)
        Unit
    }

    // S4: RootUser 查询自身 → role=root
    @Test
    fun s4_root_user_me() = runBlocking {
        val resp = callWithIdentity(rootUser, AuthMeRoute::handler)
        assertEquals("root", resp["role"]!!.jsonPrimitive.content)
        assertEquals("u-root", resp["user_id"]!!.jsonPrimitive.content)
        Unit
    }

    // S5: Guest 查询自身 → role=guest
    @Test
    fun s5_guest_me() = runBlocking {
        val resp = callWithIdentity(AuthIdentity.Guest, AuthMeRoute::handler)
        assertEquals("guest", resp["role"]!!.jsonPrimitive.content)
        Unit
    }

    // S6: 无 context → error
    @Test
    fun s6_no_context_me() = runBlocking {
        val resp = callWithoutIdentity(AuthMeRoute::handler)
        assertEquals("未知身份类型", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // ==================== AuthGuestValidateRoute ====================

    // S7: Authenticated 用户验证 → valid=true, role=tenant
    @Test
    fun s7_authenticated_guest_validate() = runBlocking {
        val resp = callWithIdentity(tenantUser, AuthGuestValidateRoute::handler)
        assertTrue(resp["valid"]!!.jsonPrimitive.boolean)
        assertEquals("tenant", resp["role"]!!.jsonPrimitive.content)
        Unit
    }

    // S8: Guest 验证 → valid=true, role=guest
    @Test
    fun s8_guest_validate() = runBlocking {
        val resp = callWithIdentity(AuthIdentity.Guest, AuthGuestValidateRoute::handler)
        assertTrue(resp["valid"]!!.jsonPrimitive.boolean)
        assertEquals("guest", resp["role"]!!.jsonPrimitive.content)
        Unit
    }

    // S9: 无 identity → valid=true, role=unknown
    @Test
    fun s9_no_identity_validate() = runBlocking {
        val resp = callWithoutIdentity(AuthGuestValidateRoute::handler)
        assertTrue(resp["valid"]!!.jsonPrimitive.boolean)
        assertEquals("unknown", resp["role"]!!.jsonPrimitive.content)
        Unit
    }
}
