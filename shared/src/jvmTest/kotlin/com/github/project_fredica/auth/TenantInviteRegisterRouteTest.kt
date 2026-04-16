package com.github.project_fredica.auth

import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.api.routes.TenantInviteRegisterRoute
import com.github.project_fredica.apputil.loadJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.ktorm.database.Database
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TenantInviteRegisterRouteTest {
    private lateinit var db: Database
    private lateinit var linkDb: TenantInviteLinkDb
    private lateinit var regDb: TenantInviteRegistrationDb
    private lateinit var userDb: UserDb
    private lateinit var tmpFile: File

    private val futureExpiry: String get() = Instant.now().plus(7, ChronoUnit.DAYS).toString()
    private val pastExpiry: String get() = Instant.now().minus(1, ChronoUnit.DAYS).toString()

    /** 简易 mock AuthService：不依赖 Python CryptoService */
    private val mockAuthService = object : AuthServiceApi {
        // 内存用户存储
        private val users = mutableMapOf<String, UserRecord>()

        override suspend fun resolveIdentity(authHeader: String?): AuthIdentity? = null

        override suspend fun createUser(
            username: String,
            displayName: String,
            password: String,
        ): CreateUserResult {
            if (users.containsKey(username)) {
                return CreateUserResult(error = "用户名已存在")
            }
            val now = Instant.now().toString()
            val user = UserRecord(
                id = UUID.randomUUID().toString(),
                username = username,
                displayName = displayName,
                role = "tenant",
                createdAt = now,
                updatedAt = now,
            )
            users[username] = user
            return CreateUserResult(success = true, user = user)
        }

        override suspend fun login(
            username: String,
            password: String,
            userAgent: String,
            ipAddress: String,
        ): LoginResult {
            val user = users[username]
                ?: return LoginResult(error = "用户名或密码错误")
            return LoginResult(
                success = true,
                token = "fredica_session:mock-token-${UUID.randomUUID()}",
                user = user,
            )
        }

        override suspend fun logout(sessionId: String) {}
        override suspend fun initializeInstance(username: String, password: String): InitResult =
            InitResult(error = "not implemented")
        override suspend fun isInstanceInitialized(): Boolean = true
        override suspend fun changePassword(
            userId: String, oldPassword: String, newPassword: String, currentSessionId: String,
        ): ChangePasswordResult = ChangePasswordResult(error = "not implemented")
    }

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_tenant_register_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        linkDb = TenantInviteLinkDb(db)
        regDb = TenantInviteRegistrationDb(db)
        userDb = UserDb(db)
        runBlocking {
            userDb.initialize()
            linkDb.initialize()
            regDb.initialize()
        }
        TenantInviteLinkService.initialize(linkDb)
        TenantInviteRegistrationService.initialize(regDb)
        AuthServiceHolder.initialize(mockAuthService)
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    private fun postParam(pathId: String, username: String, password: String, displayName: String = ""): String =
        buildString {
            append("""{"path_id":"$pathId","username":"$username","password":"$password"""")
            if (displayName.isNotEmpty()) append(""","display_name":"$displayName"""")
            append("}")
        }

    private suspend fun callHandler(
        pathId: String,
        username: String,
        password: String,
        displayName: String = "",
        ip: String = "1.1.1.1",
        ua: String = "TestAgent",
    ): JsonObject {
        val ctx = RouteContext(identity = null, clientIp = ip, userAgent = ua)
        val result = TenantInviteRegisterRoute.handler(postParam(pathId, username, password, displayName), ctx)
        return result.str.loadJson().getOrThrow() as JsonObject
    }

    // ==================== C-3: 正常注册 ====================

    // C-3.1: 有效链接 + 有效参数 → success + token + user + 注册记录
    @Test
    fun c3_1_valid_registration_returns_success() = runBlocking {
        val linkId = linkDb.create("reg-ok", "注册链接", 10, futureExpiry, "admin-1")

        val resp = callHandler("reg-ok", "newuser1", "password123", "新用户")
        assertTrue(resp["success"]!!.jsonPrimitive.boolean)
        assertNotNull(resp["token"]?.jsonPrimitive?.content)
        assertTrue(resp["token"]!!.jsonPrimitive.content.startsWith("fredica_session:"))

        // 验证注册记录
        val regs = regDb.listByLinkId(linkId)
        assertEquals(1, regs.size)
        assertEquals("1.1.1.1", regs[0].ipAddress)
        assertEquals("TestAgent", regs[0].userAgent)
        Unit
    }

    // C-3.2: 注册后 usedCount 更新
    @Test
    fun c3_2_used_count_updates_after_registration() = runBlocking {
        val linkId = linkDb.create("reg-cnt", "", 3, futureExpiry, "admin-1")

        assertEquals(0, regDb.countByLinkId(linkId))

        callHandler("reg-cnt", "user-a", "pass1")
        assertEquals(1, regDb.countByLinkId(linkId))

        callHandler("reg-cnt", "user-b", "pass2")
        assertEquals(2, regDb.countByLinkId(linkId))
        Unit
    }

    // C-3.3: 返回的 token 包含有效格式
    @Test
    fun c3_3_returned_token_has_valid_format() = runBlocking {
        linkDb.create("reg-tok", "", 10, futureExpiry, "admin-1")

        val resp = callHandler("reg-tok", "tokenuser", "pass123")
        val token = resp["token"]!!.jsonPrimitive.content
        assertTrue(token.startsWith("fredica_session:"))
        assertTrue(token.length > "fredica_session:".length)
        Unit
    }

    // ==================== C-4: 安全测试 ====================

    // C-4.1: 已禁用链接 → 直接 POST 注册被拒
    @Test
    fun c4_1_disabled_link_rejects_registration() = runBlocking {
        val id = linkDb.create("sec-dis", "", 10, futureExpiry, "admin-1")
        linkDb.updateStatus(id, "disabled")

        val resp = callHandler("sec-dis", "hacker1", "pass")
        assertEquals("邀请链接已禁用", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // C-4.2: 已过期链接 → 直接 POST 注册被拒
    @Test
    fun c4_2_expired_link_rejects_registration() = runBlocking {
        insertLinkDirectly("sec-exp", "active", 10, pastExpiry)

        val resp = callHandler("sec-exp", "hacker2", "pass")
        assertEquals("邀请链接已过期", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // C-4.3: 名额已满 → 直接 POST 注册被拒
    @Test
    fun c4_3_full_link_rejects_registration() = runBlocking {
        val id = linkDb.create("sec-full", "", 1, futureExpiry, "admin-1")
        // 填满名额
        val userId = userDb.createUser("existing-user", "EU", "hash")
        regDb.record(id, userId, "1.1.1.1", "UA")

        val resp = callHandler("sec-full", "hacker3", "pass")
        assertEquals("邀请链接已达使用上限", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // C-4.9: 空用户名 → 拒绝
    @Test
    fun c4_9_empty_username_rejected() = runBlocking {
        linkDb.create("sec-empty-u", "", 10, futureExpiry, "admin-1")

        val resp = callHandler("sec-empty-u", "", "pass")
        assertEquals("用户名不能为空", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // C-4.9b: 空白用户名（仅空格） → 拒绝
    @Test
    fun c4_9b_blank_username_rejected() = runBlocking {
        linkDb.create("sec-blank-u", "", 10, futureExpiry, "admin-1")

        val resp = callHandler("sec-blank-u", "   ", "pass")
        assertEquals("用户名不能为空", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // C-4.11: 空密码 → 拒绝
    @Test
    fun c4_11_empty_password_rejected() = runBlocking {
        linkDb.create("sec-empty-p", "", 10, futureExpiry, "admin-1")

        val resp = callHandler("sec-empty-p", "someuser", "")
        assertEquals("密码不能为空", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // C-4.14: 游客链接的 pathId → 租户注册 → "链接不存在"
    @Test
    fun c4_14_cross_type_pathId_rejected() = runBlocking {
        // 此 pathId 只存在于 guest_invite_link 表，不在 tenant_invite_link 表
        // TenantInviteRegisterRoute 查的是 TenantInviteLinkService
        val resp = callHandler("guest-only-path", "crossuser", "pass")
        assertEquals("邀请链接不存在", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // 额外：不存在的 pathId
    @Test
    fun nonexistent_pathId_returns_error() = runBlocking {
        val resp = callHandler("no-such-link", "user1", "pass1")
        assertEquals("邀请链接不存在", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // 额外：无效 JSON 参数
    @Test
    fun invalid_json_param_returns_error() = runBlocking {
        val ctx = RouteContext(identity = null, clientIp = "1.1.1.1", userAgent = "TestAgent")
        val result = TenantInviteRegisterRoute.handler("not-json", ctx)
        val resp = result.str.loadJson().getOrThrow() as JsonObject
        assertEquals("请求参数无效", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // 额外：display_name 为空时默认使用 username
    @Test
    fun empty_display_name_defaults_to_username() = runBlocking {
        linkDb.create("reg-dn", "", 10, futureExpiry, "admin-1")

        val resp = callHandler("reg-dn", "myuser", "pass123")
        assertTrue(resp["success"]!!.jsonPrimitive.boolean)
        // user 对象中 displayName 应为 username
        val userObj = resp["user"] as? JsonObject
        assertNotNull(userObj)
        assertEquals("myuser", userObj["display_name"]!!.jsonPrimitive.content)
        Unit
    }

    // 额外：重复用户名 → createUser 返回错误
    @Test
    fun duplicate_username_returns_error() = runBlocking {
        linkDb.create("reg-dup", "", 10, futureExpiry, "admin-1")

        // 第一次注册成功
        val resp1 = callHandler("reg-dup", "dupuser", "pass1")
        assertTrue(resp1["success"]!!.jsonPrimitive.boolean)

        // 第二次注册同名用户失败
        val resp2 = callHandler("reg-dup", "dupuser", "pass2")
        assertEquals("用户名已存在", resp2["error"]!!.jsonPrimitive.content)
        Unit
    }

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
                    val id = UUID.randomUUID().toString()
                    val now = Instant.now().toString()
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
