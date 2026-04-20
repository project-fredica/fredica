package com.github.project_fredica.auth

import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.api.routes.UserCreateRoute
import com.github.project_fredica.api.routes.UserDisableRoute
import com.github.project_fredica.api.routes.UserEnableRoute
import com.github.project_fredica.api.routes.UserListRoute
import com.github.project_fredica.apputil.loadJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
 * UserCreateRoute / UserDisableRoute / UserEnableRoute / UserListRoute handler 测试。
 * 需要真实 UserDb + AuthSessionDb + AuditLogDb + mock AuthServiceApi。
 */
class UserManagementRouteTest {
    private lateinit var db: Database
    private lateinit var userDb: UserDb
    private lateinit var sessionDb: AuthSessionDb
    private lateinit var auditLogDb: AuditLogDb
    private lateinit var tmpFile: File

    private val mockAuthService = object : AuthServiceApi {
        override suspend fun resolveIdentity(authHeader: String?): AuthIdentity? = null
        override suspend fun login(username: String, password: String, userAgent: String, ipAddress: String) =
            LoginResult(error = "not implemented")
        override suspend fun logout(sessionId: String) {}
        override suspend fun initializeInstance(username: String, password: String) =
            InitResult(error = "not implemented")
        override suspend fun isInstanceInitialized() = true
        override suspend fun createUser(username: String, displayName: String, password: String): CreateUserResult {
            val now = Instant.now().toString()
            return CreateUserResult(
                success = true,
                user = UserRecord(
                    id = UUID.randomUUID().toString(),
                    username = username,
                    displayName = displayName,
                    role = "tenant",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        override suspend fun changePassword(userId: String, oldPassword: String, newPassword: String, currentSessionId: String) =
            ChangePasswordResult(error = "not implemented")
    }

    private val rootUser = AuthIdentity.RootUser(
        userId = "u-root",
        username = "admin",
        displayName = "Admin",
        permissions = "*",
        sessionId = "sess-root",
    )

    private val tenantUser = AuthIdentity.TenantUser(
        userId = "u-tenant",
        username = "alice",
        displayName = "Alice",
        permissions = "read",
        sessionId = "sess-tenant",
    )

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_user_mgmt_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        userDb = UserDb(db)
        sessionDb = AuthSessionDb(db)
        auditLogDb = AuditLogDb(db)
        runBlocking {
            userDb.initialize()
            sessionDb.initialize()
            auditLogDb.initialize()
        }
        UserService.initialize(userDb)
        AuthSessionService.initialize(sessionDb)
        AuditLogService.initialize(auditLogDb)
        AuthServiceHolder.initialize(mockAuthService)
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

    // ==================== UserCreateRoute ====================

    // UM1: ROOT 创建用户成功
    @Test
    fun um1_root_create_user_success() = runBlocking {
        val resp = callWithIdentity(rootUser, UserCreateRoute::handler, """{"username":"newuser","password":"securepass1"}""")
        assertTrue(resp["success"]!!.jsonPrimitive.boolean)
        Unit
    }

    // UM2: 非 ROOT 创建用户 → 权限不足
    @Test
    fun um2_non_root_create_user() = runBlocking {
        val resp = callWithIdentity(tenantUser, UserCreateRoute::handler, """{"username":"newuser","password":"securepass1"}""")
        assertTrue(resp["error"]!!.jsonPrimitive.content.contains("权限不足"))
        Unit
    }

    // UM3: 用户名格式无效
    @Test
    fun um3_invalid_username_format() = runBlocking {
        val resp = callWithIdentity(rootUser, UserCreateRoute::handler, """{"username":"ab","password":"securepass1"}""")
        assertTrue(resp["error"]!!.jsonPrimitive.content.contains("用户名格式无效"))
        Unit
    }

    // UM4: 密码太短
    @Test
    fun um4_password_too_short() = runBlocking {
        val resp = callWithIdentity(rootUser, UserCreateRoute::handler, """{"username":"validuser","password":"short"}""")
        assertTrue(resp["error"]!!.jsonPrimitive.content.contains("密码长度需 8-128"))
        Unit
    }

    // UM5: display_name 超长（>64）
    @Test
    fun um5_display_name_too_long() = runBlocking {
        val longName = "a".repeat(65)
        val resp = callWithIdentity(rootUser, UserCreateRoute::handler, buildJsonObject {
            put("username", "validuser")
            put("password", "securepass1")
            put("display_name", longName)
        }.toString())
        assertTrue(resp["error"]!!.jsonPrimitive.content.contains("显示名长度"))
        Unit
    }

    // UM6: 审计日志 — 创建成功
    @Test
    fun um6_audit_log_user_created() = runBlocking {
        callWithIdentity(rootUser, UserCreateRoute::handler, """{"username":"audituser","password":"securepass1"}""")
        val logs = auditLogDb.query(eventType = "USER_CREATED")
        assertEquals(1, logs.total)
        assertEquals("u-root", logs.items[0].actorUserId)
        assertEquals("admin", logs.items[0].actorUsername)
        Unit
    }

    // ==================== UserDisableRoute ====================

    // UM7: ROOT 禁用用户成功
    @Test
    fun um7_root_disable_user_success() = runBlocking {
        val userId = userDb.createUser("target_user", "Target", "hash123")
        val resp = callWithIdentity(rootUser, UserDisableRoute::handler, buildJsonObject {
            put("user_id", userId)
        }.toString())
        assertTrue(resp["success"]!!.jsonPrimitive.boolean)
        // 验证状态已变更
        val user = userDb.findById(userId)!!
        assertEquals("disabled", user.status)
        Unit
    }

    // UM8: 非 ROOT 禁用用户 → 权限不足
    @Test
    fun um8_non_root_disable_user() = runBlocking {
        val resp = callWithIdentity(tenantUser, UserDisableRoute::handler, """{"user_id":"some-id"}""")
        assertTrue(resp["error"]!!.jsonPrimitive.content.contains("权限不足"))
        Unit
    }

    // UM9: 禁用自己 → 不能禁用自己
    @Test
    fun um9_disable_self() = runBlocking {
        val resp = callWithIdentity(rootUser, UserDisableRoute::handler, """{"user_id":"u-root"}""")
        assertEquals("不能禁用自己", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // UM10: 用户不存在
    @Test
    fun um10_disable_nonexistent_user() = runBlocking {
        val resp = callWithIdentity(rootUser, UserDisableRoute::handler, """{"user_id":"nonexistent"}""")
        assertEquals("用户不存在", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // UM11: 用户已禁用
    @Test
    fun um11_disable_already_disabled() = runBlocking {
        val userId = userDb.createUser("disabled_user", "DU", "hash123")
        userDb.updateStatus(userId, "disabled")
        val resp = callWithIdentity(rootUser, UserDisableRoute::handler, buildJsonObject {
            put("user_id", userId)
        }.toString())
        assertEquals("用户已处于禁用状态", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // UM7b: 禁用用户后 session 被级联删除
    @Test
    fun um7b_disable_cascades_session_delete() = runBlocking {
        val userId = userDb.createUser("sess_user", "SU", "hash123")
        sessionDb.createSession(userId, "UA", "1.1.1.1")
        // 确认 session 存在
        assertTrue(sessionDb.findByUserId(userId).isNotEmpty())
        callWithIdentity(rootUser, UserDisableRoute::handler, buildJsonObject {
            put("user_id", userId)
        }.toString())
        // session 应被删除
        assertTrue(sessionDb.findByUserId(userId).isEmpty())
        Unit
    }

    // ==================== UserEnableRoute ====================

    // UM12: ROOT 启用用户成功
    @Test
    fun um12_root_enable_user_success() = runBlocking {
        val userId = userDb.createUser("enable_user", "EU", "hash123")
        userDb.updateStatus(userId, "disabled")
        val resp = callWithIdentity(rootUser, UserEnableRoute::handler, buildJsonObject {
            put("user_id", userId)
        }.toString())
        assertTrue(resp["success"]!!.jsonPrimitive.boolean)
        val user = userDb.findById(userId)!!
        assertEquals("active", user.status)
        Unit
    }

    // UM13: 非 ROOT 启用用户 → 权限不足
    @Test
    fun um13_non_root_enable_user() = runBlocking {
        val resp = callWithIdentity(tenantUser, UserEnableRoute::handler, """{"user_id":"some-id"}""")
        assertTrue(resp["error"]!!.jsonPrimitive.content.contains("权限不足"))
        Unit
    }

    // UM14: 用户不存在
    @Test
    fun um14_enable_nonexistent_user() = runBlocking {
        val resp = callWithIdentity(rootUser, UserEnableRoute::handler, """{"user_id":"nonexistent"}""")
        assertEquals("用户不存在", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // UM15: 用户已启用
    @Test
    fun um15_enable_already_active() = runBlocking {
        val userId = userDb.createUser("active_user", "AU", "hash123")
        val resp = callWithIdentity(rootUser, UserEnableRoute::handler, buildJsonObject {
            put("user_id", userId)
        }.toString())
        assertEquals("用户已处于启用状态", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // ==================== UserListRoute ====================

    // UM16: ROOT 列出用户
    @Test
    fun um16_root_list_users() = runBlocking {
        userDb.createUser("list_user_1", "LU1", "hash1")
        userDb.createUser("list_user_2", "LU2", "hash2")
        val result = UserListRoute.handler("", ctx(rootUser))
        val arr = result.str.loadJson().getOrThrow() as kotlinx.serialization.json.JsonArray
        assertTrue(arr.size >= 2)
        Unit
    }

    // UM17: 非 ROOT 列出用户 → 权限不足
    @Test
    fun um17_non_root_list_users() = runBlocking {
        val resp = callWithIdentity(tenantUser, UserListRoute::handler)
        assertTrue(resp["error"]!!.jsonPrimitive.content.contains("权限不足"))
        Unit
    }
}
