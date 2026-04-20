package com.github.project_fredica.auth

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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * InstanceStatusRoute handler 测试。
 *
 * InstanceInitRoute 已删除，初始化逻辑迁移至 InstanceInitJsMessageHandler（仅 WebView JsBridge 可调用）。
 */
class InstanceRouteTest {
    private lateinit var db: Database
    private lateinit var appConfigDb: AppConfigDb
    private lateinit var tmpFile: File

    private var instanceInitialized = false

    private val mockAuthService = object : AuthServiceApi {
        override suspend fun resolveIdentity(authHeader: String?): AuthIdentity? = null
        override suspend fun login(username: String, password: String, userAgent: String, ipAddress: String) =
            LoginResult(error = "not implemented")
        override suspend fun logout(sessionId: String) {}
        override suspend fun initializeInstance(username: String, password: String): InitResult = InitResult(error = "not implemented")
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
        runBlocking { appConfigDb.initialize() }
        AppConfigService.initialize(appConfigDb)
        AuthServiceHolder.initialize(mockAuthService)
        instanceInitialized = false
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
}
