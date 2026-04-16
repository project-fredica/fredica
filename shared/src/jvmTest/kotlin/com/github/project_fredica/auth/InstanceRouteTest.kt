package com.github.project_fredica.auth

import com.github.project_fredica.api.routes.InstanceInitRoute
import com.github.project_fredica.api.routes.InstanceStatusRoute
import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.apputil.loadJson
import com.github.project_fredica.db.AppConfigDb
import com.github.project_fredica.db.AppConfigService
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * InstanceStatusRoute / InstanceInitRoute handler 测试。
 */
class InstanceRouteTest {
    private lateinit var db: Database
    private lateinit var appConfigDb: AppConfigDb
    private lateinit var auditLogDb: AuditLogDb
    private lateinit var tmpFile: File

    // 可控的 mock
    private var instanceInitialized = false
    private var initResult: InitResult = InitResult(error = "not implemented")

    private val mockAuthService = object : AuthServiceApi {
        override suspend fun resolveIdentity(authHeader: String?): AuthIdentity? = null
        override suspend fun login(username: String, password: String, userAgent: String, ipAddress: String) =
            LoginResult(error = "not implemented")
        override suspend fun logout(sessionId: String) {}
        override suspend fun initializeInstance(username: String, password: String): InitResult = initResult
        override suspend fun isInstanceInitialized(): Boolean = instanceInitialized
        override suspend fun createUser(username: String, displayName: String, password: String) =
            CreateUserResult(error = "not implemented")
        override suspend fun changePassword(userId: String, oldPassword: String, newPassword: String, currentSessionId: String) =
            ChangePasswordResult(error = "not implemented")
    }

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_instance_route_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        appConfigDb = AppConfigDb(db)
        auditLogDb = AuditLogDb(db)
        runBlocking {
            appConfigDb.initialize()
            auditLogDb.initialize()
            AppConfigService.initialize(appConfigDb)
        }
        AuditLogService.initialize(auditLogDb)
        AuthServiceHolder.initialize(mockAuthService)
        instanceInitialized = false
        initResult = InitResult(error = "not implemented")
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    private val defaultContext = RouteContext(identity = null, clientIp = null, userAgent = null)

    private suspend fun callStatus(): JsonObject {
        val result = InstanceStatusRoute.handler("", defaultContext)
        return result.str.loadJson().getOrThrow() as JsonObject
    }

    private suspend fun callInit(param: String): JsonObject {
        val result = InstanceInitRoute.handler(param, defaultContext)
        return result.str.loadJson().getOrThrow() as JsonObject
    }

    // ==================== InstanceStatusRoute ====================

    // IN1: 未初始化实例
    @Test
    fun in1_not_initialized() = runBlocking {
        instanceInitialized = false
        val resp = callStatus()
        assertFalse(resp["initialized"]!!.jsonPrimitive.boolean)
        assertFalse(resp["guest_token_configured"]!!.jsonPrimitive.boolean)
        Unit
    }

    // IN2: 已初始化 + 有 guest token
    @Test
    fun in2_initialized_with_guest_token() = runBlocking {
        instanceInitialized = true
        appConfigDb.updateConfigPartial(mapOf("webserver_auth_token" to "some-token"))
        val resp = callStatus()
        assertTrue(resp["initialized"]!!.jsonPrimitive.boolean)
        assertTrue(resp["guest_token_configured"]!!.jsonPrimitive.boolean)
        Unit
    }

    // IN3: 已初始化 + 无 guest token
    @Test
    fun in3_initialized_without_guest_token() = runBlocking {
        instanceInitialized = true
        val resp = callStatus()
        assertTrue(resp["initialized"]!!.jsonPrimitive.boolean)
        assertFalse(resp["guest_token_configured"]!!.jsonPrimitive.boolean)
        Unit
    }

    // ==================== InstanceInitRoute ====================

    // IN4: 正常初始化
    @Test
    fun in4_init_success() = runBlocking {
        val now = Instant.now().toString()
        initResult = InitResult(
            success = true,
            token = "fredica_session:init-token",
            user = UserRecord(
                id = "u-root-new",
                username = "myadmin",
                displayName = "myadmin",
                role = "root",
                createdAt = now,
                updatedAt = now,
            ),
            webserverAuthToken = "guest-token-auto",
        )
        val resp = callInit("""{"username":"myadmin","password":"securepass1"}""")
        assertTrue(resp["success"]!!.jsonPrimitive.boolean)
        assertNotNull(resp["token"]?.jsonPrimitive?.content)
        Unit
    }

    // IN5: 用户名太短（<3）
    @Test
    fun in5_username_too_short() = runBlocking {
        val resp = callInit("""{"username":"ab","password":"securepass1"}""")
        assertTrue(resp["error"]!!.jsonPrimitive.content.contains("用户名格式无效"))
        Unit
    }

    // IN6: 用户名含非法字符
    @Test
    fun in6_username_invalid_chars() = runBlocking {
        val resp = callInit("""{"username":"user@name","password":"securepass1"}""")
        assertTrue(resp["error"]!!.jsonPrimitive.content.contains("用户名格式无效"))
        Unit
    }

    // IN7: 密码太短（<8）
    @Test
    fun in7_password_too_short() = runBlocking {
        val resp = callInit("""{"username":"validuser","password":"short"}""")
        assertTrue(resp["error"]!!.jsonPrimitive.content.contains("密码长度需 8-128"))
        Unit
    }

    // IN8: 请求参数无效
    @Test
    fun in8_invalid_param() = runBlocking {
        val resp = callInit("not-json")
        assertEquals("请求参数无效", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // IN9: 审计日志 — 初始化成功
    @Test
    fun in9_audit_log_on_init() = runBlocking {
        val now = Instant.now().toString()
        initResult = InitResult(
            success = true,
            token = "fredica_session:init-token",
            user = UserRecord(
                id = "u-root-audit",
                username = "auditadmin",
                displayName = "auditadmin",
                role = "root",
                createdAt = now,
                updatedAt = now,
            ),
        )
        callInit("""{"username":"auditadmin","password":"securepass1"}""")
        val logs = auditLogDb.query(eventType = "INSTANCE_INITIALIZED")
        assertEquals(1, logs.total)
        assertEquals("u-root-audit", logs.items[0].actorUserId)
        assertEquals("auditadmin", logs.items[0].actorUsername)
        Unit
    }
}
