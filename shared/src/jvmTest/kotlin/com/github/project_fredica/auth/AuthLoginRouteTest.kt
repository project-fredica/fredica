package com.github.project_fredica.auth

import com.github.project_fredica.api.routes.AuthLoginRoute
import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.apputil.loadJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.ktorm.database.Database
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AuthLoginRoute handler 测试。
 * 需要 mock AuthServiceApi + LoginRateLimiterApi + 真实 AuditLogDb。
 */
class AuthLoginRouteTest {
    private lateinit var db: Database
    private lateinit var auditLogDb: AuditLogDb
    private lateinit var tmpFile: File

    // 跟踪 rateLimiter 调用
    private var clearOnSuccessCalled = false
    private var recordFailureCalled = false

    // 可控的 rateLimiter
    private var rateLimitWaitSeconds: Int? = null

    private val mockRateLimiter = object : LoginRateLimiterApi {
        override fun check(ip: String, username: String): Int? = rateLimitWaitSeconds
        override fun recordFailure(ip: String, username: String) { recordFailureCalled = true }
        override fun clearOnSuccess(ip: String, username: String) { clearOnSuccessCalled = true }
    }

    private val mockAuthService = object : AuthServiceApi {
        override suspend fun resolveIdentity(authHeader: String?): AuthIdentity? = null
        override suspend fun login(username: String, password: String, userAgent: String, ipAddress: String): LoginResult {
            if (username == "alice" && password == "correct-pass") {
                val now = Instant.now().toString()
                return LoginResult(
                    success = true,
                    token = "fredica_session:mock-${UUID.randomUUID()}",
                    user = UserRecord(
                        id = "u-alice",
                        username = "alice",
                        displayName = "Alice",
                        role = "tenant",
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
            return LoginResult(error = "用户名或密码错误")
        }
        override suspend fun logout(sessionId: String) {}
        override suspend fun initializeInstance(username: String, password: String) = InitResult(error = "not implemented")
        override suspend fun isInstanceInitialized() = true
        override suspend fun createUser(username: String, displayName: String, password: String) = CreateUserResult(error = "not implemented")
        override suspend fun changePassword(userId: String, oldPassword: String, newPassword: String, currentSessionId: String) = ChangePasswordResult(error = "not implemented")
    }

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_login_route_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        auditLogDb = AuditLogDb(db)
        runBlocking { auditLogDb.initialize() }
        AuditLogService.initialize(auditLogDb)
        AuthServiceHolder.initialize(mockAuthService)
        LoginRateLimiterHolder.initialize(mockRateLimiter)
        rateLimitWaitSeconds = null
        clearOnSuccessCalled = false
        recordFailureCalled = false
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    private suspend fun callLogin(username: String, password: String, ip: String = "1.1.1.1", ua: String = "TestAgent"): JsonObject {
        val param = """{"username":"$username","password":"$password","ip_address":"$ip","user_agent":"$ua"}"""
        val result = AuthLoginRoute.handler(param, RouteContext(identity = null, clientIp = ip, userAgent = ua))
        return result.str.loadJson().getOrThrow() as JsonObject
    }

    // L1: 正常登录成功
    @Test
    fun l1_login_success() = runBlocking {
        val resp = callLogin("alice", "correct-pass")
        assertTrue(resp["success"]!!.jsonPrimitive.boolean)
        assertNotNull(resp["token"]?.jsonPrimitive?.content)
        assertTrue(resp["token"]!!.jsonPrimitive.content.startsWith("fredica_session:"))
        assertNotNull(resp["user"])
        Unit
    }

    // L2: 用户名或密码错误
    @Test
    fun l2_login_wrong_credentials() = runBlocking {
        val resp = callLogin("alice", "wrong-pass")
        assertEquals("用户名或密码错误", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // L3: 请求参数无效
    @Test
    fun l3_invalid_param() = runBlocking {
        val result = AuthLoginRoute.handler("not-json", RouteContext(identity = null, clientIp = null, userAgent = null))
        val resp = result.str.loadJson().getOrThrow() as JsonObject
        assertEquals("请求参数无效", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // L4: 速率限制触发
    @Test
    fun l4_rate_limited() = runBlocking {
        rateLimitWaitSeconds = 30
        val resp = callLogin("alice", "correct-pass")
        assertTrue(resp["error"]!!.jsonPrimitive.content.contains("登录尝试过于频繁"))
        assertEquals(30, resp["retry_after"]!!.jsonPrimitive.int)
        Unit
    }

    // L5: 登录成功后清除速率限制
    @Test
    fun l5_clear_on_success() = runBlocking {
        callLogin("alice", "correct-pass")
        assertTrue(clearOnSuccessCalled)
        Unit
    }

    // L6: 登录失败后记录失败
    @Test
    fun l6_record_failure() = runBlocking {
        callLogin("alice", "wrong-pass")
        assertTrue(recordFailureCalled)
        Unit
    }

    // L7: 审计日志 — 成功
    @Test
    fun l7_audit_log_success() = runBlocking {
        callLogin("alice", "correct-pass")
        val logs = auditLogDb.query(eventType = "LOGIN_SUCCESS")
        assertEquals(1, logs.total)
        assertEquals("alice", logs.items[0].actorUsername)
        assertEquals("u-alice", logs.items[0].actorUserId)
        Unit
    }

    // L8: 审计日志 — 失败
    @Test
    fun l8_audit_log_failure() = runBlocking {
        callLogin("alice", "wrong-pass")
        val logs = auditLogDb.query(eventType = "LOGIN_FAILED")
        assertEquals(1, logs.total)
        assertEquals("alice", logs.items[0].actorUsername)
        Unit
    }
}
