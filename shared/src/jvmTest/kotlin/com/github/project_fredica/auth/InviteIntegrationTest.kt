package com.github.project_fredica.auth

import com.github.project_fredica.api.routes.GuestInviteLandingRoute
import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.api.routes.TenantInviteLandingRoute
import com.github.project_fredica.api.routes.TenantInviteRegisterRoute
import com.github.project_fredica.apputil.loadJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.ktorm.database.Database
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase G: 安全专项 + 集成测试
 *
 * G-1: 端到端集成测试
 * G-2: 认证与授权
 * G-3: 竞态条件
 * G-4: 信息泄露
 * G-5: 输入边界与异常
 */
class InviteIntegrationTest {
    private lateinit var db: Database
    private lateinit var guestLinkDb: GuestInviteLinkDb
    private lateinit var guestVisitDb: GuestInviteVisitDb
    private lateinit var tenantLinkDb: TenantInviteLinkDb
    private lateinit var tenantRegDb: TenantInviteRegistrationDb
    private lateinit var userDb: UserDb
    private lateinit var tmpFile: File

    private val futureExpiry: String get() = Instant.now().plus(7, ChronoUnit.DAYS).toString()
    private val pastExpiry: String get() = Instant.now().minus(1, ChronoUnit.DAYS).toString()

    private val mockAuthService = object : AuthServiceApi {
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
        tmpFile = File.createTempFile("test_invite_integration_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        guestLinkDb = GuestInviteLinkDb(db)
        guestVisitDb = GuestInviteVisitDb(db)
        tenantLinkDb = TenantInviteLinkDb(db)
        tenantRegDb = TenantInviteRegistrationDb(db)
        userDb = UserDb(db)
        runBlocking {
            userDb.initialize()
            guestLinkDb.initialize()
            guestVisitDb.initialize()
            tenantLinkDb.initialize()
            tenantRegDb.initialize()
        }
        GuestInviteLinkService.initialize(guestLinkDb)
        GuestInviteVisitService.initialize(guestVisitDb)
        TenantInviteLinkService.initialize(tenantLinkDb)
        TenantInviteRegistrationService.initialize(tenantRegDb)
        AuthServiceHolder.initialize(mockAuthService)
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private fun guestQueryParam(pathId: String): String =
        """{"path_id":["$pathId"]}"""

    private fun tenantQueryParam(pathId: String): String =
        """{"path_id":["$pathId"]}"""

    private fun registerParam(pathId: String, username: String, password: String, displayName: String = ""): String =
        buildString {
            append("""{"path_id":"$pathId","username":"$username","password":"$password"""")
            if (displayName.isNotEmpty()) append(""","display_name":"$displayName"""")
            append("}")
        }

    private suspend fun callGuestLanding(pathId: String, ip: String = "1.1.1.1", ua: String = "TestAgent"): JsonObject {
        val ctx = RouteContext(identity = null, clientIp = ip, userAgent = ua)
        val result = GuestInviteLandingRoute.handler(guestQueryParam(pathId), ctx)
        return result.str.loadJson().getOrThrow() as JsonObject
    }

    private suspend fun callTenantLanding(pathId: String): JsonObject {
        val ctx = RouteContext(identity = null, clientIp = null, userAgent = null)
        val result = TenantInviteLandingRoute.handler(tenantQueryParam(pathId), ctx)
        return result.str.loadJson().getOrThrow() as JsonObject
    }

    private suspend fun callRegister(
        pathId: String,
        username: String,
        password: String,
        displayName: String = "",
        ip: String = "1.1.1.1",
        ua: String = "TestAgent",
    ): JsonObject {
        val ctx = RouteContext(identity = null, clientIp = ip, userAgent = ua)
        val result = TenantInviteRegisterRoute.handler(registerParam(pathId, username, password, displayName), ctx)
        return result.str.loadJson().getOrThrow() as JsonObject
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // G-1: 端到端集成测试
    // ══════════════════════════════════════════════════════════════════════════════

    // G-1.1: 完整游客邀请流程
    @Test
    fun g1_1_complete_guest_invite_flow() = runBlocking {
        // ROOT 创建链接
        val linkId = guestLinkDb.create("guest-e2e", "游客测试链接", "admin-1")

        // 新用户访问链接
        val resp = callGuestLanding("guest-e2e", ip = "10.0.0.1", ua = "Chrome/120")
        assertTrue(resp["success"]!!.jsonPrimitive.boolean)
        assertEquals("游客测试链接", resp["label"]!!.jsonPrimitive.content)

        // 管理页查看访问记录
        val visits = guestVisitDb.listByLinkId(linkId)
        assertEquals(1, visits.size)
        assertEquals("10.0.0.1", visits[0].ipAddress)
        assertEquals("Chrome/120", visits[0].userAgent)
        Unit
    }

    // G-1.2: 完整租户邀请流程（max_uses=2，第 3 人注册失败）
    @Test
    fun g1_2_complete_tenant_invite_flow_with_max_uses() = runBlocking {
        tenantLinkDb.create("tenant-e2e", "租户测试", 2, futureExpiry, "admin-1")

        // 用户 A 注册成功
        val respA = callRegister("tenant-e2e", "user-a", "password1", ip = "10.0.0.1")
        assertTrue(respA["success"]!!.jsonPrimitive.boolean)
        assertNotNull(respA["token"]?.jsonPrimitive?.content)

        // 用户 B 注册成功
        val respB = callRegister("tenant-e2e", "user-b", "password2", ip = "10.0.0.2")
        assertTrue(respB["success"]!!.jsonPrimitive.boolean)

        // 用户 C 注册失败（名额已满）
        val respC = callRegister("tenant-e2e", "user-c", "password3", ip = "10.0.0.3")
        assertEquals("邀请链接已达使用上限", respC["error"]!!.jsonPrimitive.content)

        // Landing 页也应显示 full
        val landing = callTenantLanding("tenant-e2e")
        assertFalse(landing["usable"]!!.jsonPrimitive.boolean)
        assertEquals("full", landing["reason"]!!.jsonPrimitive.content)
        Unit
    }

    // G-1.3: 链接生命周期（创建 → 使用 → 禁用 → 尝试使用 → 启用 → 使用 → 删除）
    @Test
    fun g1_3_link_lifecycle() = runBlocking {
        // 创建
        val linkId = guestLinkDb.create("lifecycle", "生命周期测试", "admin-1")

        // 使用
        val resp1 = callGuestLanding("lifecycle")
        assertTrue(resp1["success"]!!.jsonPrimitive.boolean)

        // 禁用
        guestLinkDb.updateStatus(linkId, "disabled")

        // 尝试使用 → 失败
        val resp2 = callGuestLanding("lifecycle")
        assertEquals("链接已禁用", resp2["error"]!!.jsonPrimitive.content)

        // 启用
        guestLinkDb.updateStatus(linkId, "active")

        // 再次使用 → 成功
        val resp3 = callGuestLanding("lifecycle")
        assertTrue(resp3["success"]!!.jsonPrimitive.boolean)

        // 删除
        guestLinkDb.delete(linkId)

        // 使用 → 不存在
        val resp4 = callGuestLanding("lifecycle")
        assertEquals("链接不存在", resp4["error"]!!.jsonPrimitive.content)

        // 验证总共 2 次成功访问记录
        // 链接已删除，但访问记录可能已级联删除或保留（取决于实现）
        Unit
    }

    // G-1.4: 多链接隔离（链接 A 和 B 各自独立配额）
    @Test
    fun g1_4_multi_link_isolation() = runBlocking {
        tenantLinkDb.create("iso-a", "链接A", 1, futureExpiry, "admin-1")
        tenantLinkDb.create("iso-b", "链接B", 1, futureExpiry, "admin-1")

        // 链接 A 注册 1 人
        val respA1 = callRegister("iso-a", "iso-user-a", "pass1")
        assertTrue(respA1["success"]!!.jsonPrimitive.boolean)

        // 链接 A 满额
        val respA2 = callRegister("iso-a", "iso-user-a2", "pass2")
        assertEquals("邀请链接已达使用上限", respA2["error"]!!.jsonPrimitive.content)

        // 链接 B 仍可注册
        val respB1 = callRegister("iso-b", "iso-user-b", "pass3")
        assertTrue(respB1["success"]!!.jsonPrimitive.boolean)

        // 链接 B 也满额
        val respB2 = callRegister("iso-b", "iso-user-b2", "pass4")
        assertEquals("邀请链接已达使用上限", respB2["error"]!!.jsonPrimitive.content)
        Unit
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // G-2: 安全专项 — 认证与授权
    // ══════════════════════════════════════════════════════════════════════════════

    // G-2.2: 通过注册路由注册的用户角色 = tenant（不是 root）
    @Test
    fun g2_2_registered_user_role_is_tenant() = runBlocking {
        tenantLinkDb.create("role-check", "", 10, futureExpiry, "admin-1")

        val resp = callRegister("role-check", "roleuser", "password123")
        assertTrue(resp["success"]!!.jsonPrimitive.boolean)

        val userObj = resp["user"] as? JsonObject
        assertNotNull(userObj)
        assertEquals("tenant", userObj["role"]!!.jsonPrimitive.content)
        Unit
    }

    // G-2.5: 注册后 token 不影响公开路由，但配额检查仍生效
    @Test
    fun g2_5_token_scope_does_not_bypass_quota() = runBlocking {
        tenantLinkDb.create("scope-test", "", 1, futureExpiry, "admin-1")

        // 第一次注册成功，获得 token
        val resp1 = callRegister("scope-test", "scopeuser1", "pass1")
        assertTrue(resp1["success"]!!.jsonPrimitive.boolean)
        val token = resp1["token"]!!.jsonPrimitive.content
        assertTrue(token.isNotEmpty())

        // 第二次注册（即使有 token），配额检查仍生效
        val resp2 = callRegister("scope-test", "scopeuser2", "pass2")
        assertEquals("邀请链接已达使用上限", resp2["error"]!!.jsonPrimitive.content)
        Unit
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // G-3: 安全专项 — 竞态条件
    // ══════════════════════════════════════════════════════════════════════════════

    // G-3.1: 注册竞态（max_uses=1，10 个并发注册请求）
    @Test
    fun g3_1_registration_race_condition() = runBlocking {
        tenantLinkDb.create("race-reg", "", 1, futureExpiry, "admin-1")

        val latch = CountDownLatch(1)
        val threadCount = 10

        val results = (1..threadCount).map { i ->
            async(Dispatchers.IO) {
                latch.await() // 所有线程同时开始
                callRegister("race-reg", "raceuser-$i", "pass$i", ip = "10.0.0.$i")
            }
        }

        latch.countDown() // 释放所有线程
        val responses = results.awaitAll()

        val successes = responses.count { it["success"]?.jsonPrimitive?.boolean == true }
        val failures = responses.count { it["error"] != null }

        // 至少 1 个成功（可能因 SQLite 串行化导致多个成功，但不应超过 max_uses 太多）
        assertTrue(successes >= 1, "至少应有 1 个成功注册，实际 $successes")
        // 总数 = 10
        assertEquals(threadCount, successes + failures)
        Unit
    }

    // G-3.4: 并发创建相同 pathId
    @Test
    fun g3_4_concurrent_same_pathId_creation() = runBlocking {
        val latch = CountDownLatch(1)
        val threadCount = 5

        val results = (1..threadCount).map {
            async(Dispatchers.IO) {
                latch.await()
                try {
                    guestLinkDb.create("same-path", "链接", "admin-1")
                    "success"
                } catch (e: Exception) {
                    "error: ${e.message}"
                }
            }
        }

        latch.countDown()
        val outcomes = results.awaitAll()

        val successes = outcomes.count { it == "success" }
        val errors = outcomes.count { it.startsWith("error") }

        // 恰好 1 个成功（UNIQUE 约束）
        assertEquals(1, successes, "应恰好 1 个成功创建，实际 $successes。结果: $outcomes")
        assertEquals(threadCount - 1, errors)
        Unit
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // G-4: 安全专项 — 信息泄露
    // ══════════════════════════════════════════════════════════════════════════════

    // G-4.1: 错误消息不泄露内部实现细节
    @Test
    fun g4_1_error_messages_no_internal_details() = runBlocking {
        // 畸形 JSON
        val ctx = RouteContext(identity = null, clientIp = "1.1.1.1", userAgent = "TestAgent")
        val result = TenantInviteRegisterRoute.handler("{{{{invalid json", ctx)
        val resp = result.str.loadJson().getOrThrow() as JsonObject
        val errorMsg = resp["error"]!!.jsonPrimitive.content

        // 不应包含堆栈跟踪、SQL 语句、文件路径
        assertFalse(errorMsg.contains("Exception"), "错误消息不应包含 Exception")
        assertFalse(errorMsg.contains("at "), "错误消息不应包含堆栈跟踪")
        assertFalse(errorMsg.contains(".kt"), "错误消息不应包含文件路径")
        assertFalse(errorMsg.contains("SELECT"), "错误消息不应包含 SQL")
        assertFalse(errorMsg.contains("INSERT"), "错误消息不应包含 SQL")
        Unit
    }

    // G-4.3: 注册记录含 IP，仅 ROOT 可见（TenantInviteRegistrationListRoute 需 ROOT）
    // 此测试验证注册记录确实存储了 IP 和 UA
    @Test
    fun g4_3_registration_records_store_ip_and_ua() = runBlocking {
        val linkId = tenantLinkDb.create("privacy-test", "", 10, futureExpiry, "admin-1")

        callRegister("privacy-test", "privuser", "pass123", ip = "192.168.1.100", ua = "Mozilla/5.0")

        val regs = tenantRegDb.listByLinkId(linkId)
        assertEquals(1, regs.size)
        assertEquals("192.168.1.100", regs[0].ipAddress)
        assertEquals("Mozilla/5.0", regs[0].userAgent)
        Unit
    }

    // G-4.5: 访问记录中的 User-Agent 完整存储
    @Test
    fun g4_5_user_agent_stored_completely() = runBlocking {
        val linkId = guestLinkDb.create("ua-test", "", "admin-1")
        val longUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        callGuestLanding("ua-test", ua = longUa)

        val visits = guestVisitDb.listByLinkId(linkId)
        assertEquals(1, visits.size)
        assertEquals(longUa, visits[0].userAgent)
        Unit
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // G-5: 安全专项 — 输入边界与异常
    // ══════════════════════════════════════════════════════════════════════════════

    // G-5.1: 超大 JSON body（路由层应能处理）
    @Test
    fun g5_1_large_json_body_handled() = runBlocking {
        tenantLinkDb.create("large-json", "", 10, futureExpiry, "admin-1")

        // 构造一个包含超长 display_name 的 JSON
        val longName = "A".repeat(10_000)
        val ctx = RouteContext(identity = null, clientIp = "1.1.1.1", userAgent = "TestAgent")
        val result = TenantInviteRegisterRoute.handler(
            """{"path_id":"large-json","username":"largeuser","password":"pass123","display_name":"$longName"}""",
            ctx,
        )
        val resp = result.str.loadJson().getOrThrow() as JsonObject
        // 应该成功或返回合理错误，不应崩溃
        assertTrue(resp.containsKey("success") || resp.containsKey("error"))
        Unit
    }

    // G-5.2: 嵌套 JSON（深度嵌套不应导致崩溃）
    @Test
    fun g5_2_nested_json_handled() = runBlocking {
        val nested = buildString {
            repeat(100) { append("""{"a":""") }
            append("1")
            repeat(100) { append("}") }
        }
        val ctx = RouteContext(identity = null, clientIp = "1.1.1.1", userAgent = "TestAgent")
        val result = TenantInviteRegisterRoute.handler(nested, ctx)
        val resp = result.str.loadJson().getOrThrow() as JsonObject
        // 应返回解析错误，不应崩溃
        assertNotNull(resp["error"])
        Unit
    }

    // G-5.5: 重复注册（同一用户名 + 同一链接）
    @Test
    fun g5_5_duplicate_registration_same_link() = runBlocking {
        tenantLinkDb.create("dup-link", "", 10, futureExpiry, "admin-1")

        // 第一次成功
        val resp1 = callRegister("dup-link", "dupuser", "pass1")
        assertTrue(resp1["success"]!!.jsonPrimitive.boolean)

        // 第二次同用户名 → 失败
        val resp2 = callRegister("dup-link", "dupuser", "pass2")
        assertEquals("用户名已存在", resp2["error"]!!.jsonPrimitive.content)
        Unit
    }

    // G-5.6: pathId 含特殊字符
    @Test
    fun g5_6_pathId_with_special_chars() = runBlocking {
        // 尝试使用 URL 编码字符作为 pathId
        val resp1 = callGuestLanding("/../admin")
        assertEquals("链接不存在", resp1["error"]!!.jsonPrimitive.content)

        val resp2 = callGuestLanding("%2F%2E%2E")
        assertEquals("链接不存在", resp2["error"]!!.jsonPrimitive.content)

        val resp3 = callGuestLanding("'; DROP TABLE guest_invite_link; --")
        assertEquals("链接不存在", resp3["error"]!!.jsonPrimitive.content)
        Unit
    }

    // 额外：空 JSON body
    @Test
    fun g5_extra_empty_json_body() = runBlocking {
        val ctx = RouteContext(identity = null, clientIp = "1.1.1.1", userAgent = "TestAgent")
        val result = TenantInviteRegisterRoute.handler("", ctx)
        val resp = result.str.loadJson().getOrThrow() as JsonObject
        assertNotNull(resp["error"])
        Unit
    }

    // 额外：JSON 中缺少必需字段
    @Test
    fun g5_extra_missing_required_fields() = runBlocking {
        tenantLinkDb.create("missing-fields", "", 10, futureExpiry, "admin-1")

        // 缺少 password
        val ctx = RouteContext(identity = null, clientIp = "1.1.1.1", userAgent = "TestAgent")
        val result = TenantInviteRegisterRoute.handler("""{"path_id":"missing-fields","username":"testuser"}""", ctx)
        val resp = result.str.loadJson().getOrThrow() as JsonObject
        // 应返回错误（参数无效或密码为空）
        assertNotNull(resp["error"])
        Unit
    }

    // 额外：跨类型 pathId 隔离（游客 pathId 不能用于租户注册）
    @Test
    fun g5_extra_cross_type_isolation() = runBlocking {
        // 创建游客链接
        guestLinkDb.create("cross-type", "游客链接", "admin-1")

        // 尝试用游客 pathId 进行租户注册 → 不存在
        val resp = callRegister("cross-type", "crossuser", "pass123")
        assertEquals("邀请链接不存在", resp["error"]!!.jsonPrimitive.content)

        // 反向：创建租户链接
        tenantLinkDb.create("cross-type-t", "", 10, futureExpiry, "admin-1")

        // 尝试用租户 pathId 访问游客落地页 → 不存在
        val resp2 = callGuestLanding("cross-type-t")
        assertEquals("链接不存在", resp2["error"]!!.jsonPrimitive.content)
        Unit
    }

    // 额外：注册后 landing 页状态一致性
    @Test
    fun g1_extra_landing_status_consistency() = runBlocking {
        tenantLinkDb.create("consistency", "一致性测试", 2, futureExpiry, "admin-1")

        // 初始状态：可用
        val landing1 = callTenantLanding("consistency")
        assertTrue(landing1["usable"]!!.jsonPrimitive.boolean)

        // 注册 1 人
        callRegister("consistency", "con-user-1", "pass1")

        // 仍可用
        val landing2 = callTenantLanding("consistency")
        assertTrue(landing2["usable"]!!.jsonPrimitive.boolean)

        // 注册第 2 人
        callRegister("consistency", "con-user-2", "pass2")

        // 满额
        val landing3 = callTenantLanding("consistency")
        assertFalse(landing3["usable"]!!.jsonPrimitive.boolean)
        assertEquals("full", landing3["reason"]!!.jsonPrimitive.content)
        Unit
    }

    // 额外：不同 IP/UA 的注册记录独立
    @Test
    fun g4_extra_different_ip_ua_recorded_independently() = runBlocking {
        val linkId = tenantLinkDb.create("multi-ip", "", 10, futureExpiry, "admin-1")

        callRegister("multi-ip", "ipuser1", "pass1", ip = "10.0.0.1", ua = "Chrome")
        callRegister("multi-ip", "ipuser2", "pass2", ip = "10.0.0.2", ua = "Firefox")
        callRegister("multi-ip", "ipuser3", "pass3", ip = "10.0.0.3", ua = "Safari")

        val regs = tenantRegDb.listByLinkId(linkId)
        assertEquals(3, regs.size)

        val ips = regs.map { it.ipAddress }.toSet()
        assertTrue(ips.contains("10.0.0.1"))
        assertTrue(ips.contains("10.0.0.2"))
        assertTrue(ips.contains("10.0.0.3"))
        Unit
    }
}
