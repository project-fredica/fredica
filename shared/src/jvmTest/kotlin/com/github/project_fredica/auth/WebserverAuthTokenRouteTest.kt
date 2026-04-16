package com.github.project_fredica.auth

import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.api.routes.WebserverAuthTokenGetRoute
import com.github.project_fredica.api.routes.WebserverAuthTokenUpdateRoute
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WebserverAuthTokenGetRoute / WebserverAuthTokenUpdateRoute handler 测试。
 */
class WebserverAuthTokenRouteTest {
    private lateinit var db: Database
    private lateinit var appConfigDb: AppConfigDb
    private lateinit var tmpFile: File

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
        tmpFile = File.createTempFile("test_ws_token_route_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        appConfigDb = AppConfigDb(db)
        runBlocking {
            appConfigDb.initialize()
            AppConfigService.initialize(appConfigDb)
        }
        WebserverAuthTokenCache.invalidate()
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    private fun ctx(identity: AuthIdentity) = RouteContext(identity = identity, clientIp = null, userAgent = null)

    private suspend fun callGet(identity: AuthIdentity): JsonObject {
        val result = WebserverAuthTokenGetRoute.handler("", ctx(identity))
        return result.str.loadJson().getOrThrow() as JsonObject
    }

    private suspend fun callUpdate(identity: AuthIdentity, param: String): JsonObject {
        val result = WebserverAuthTokenUpdateRoute.handler(param, ctx(identity))
        return result.str.loadJson().getOrThrow() as JsonObject
    }

    // WT1: ROOT 读取 token
    @Test
    fun wt1_root_get_token() = runBlocking {
        appConfigDb.updateConfigPartial(mapOf("webserver_auth_token" to "my-secret-token"))
        val resp = callGet(rootUser)
        assertEquals("my-secret-token", resp["webserver_auth_token"]!!.jsonPrimitive.content)
        Unit
    }

    // WT2: 非 ROOT 读取 token → 权限不足
    @Test
    fun wt2_non_root_get_token() = runBlocking {
        val resp = callGet(tenantUser)
        assertEquals("权限不足", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // WT3: ROOT 更新 token → success + DB 已更新
    @Test
    fun wt3_root_update_token() = runBlocking {
        val resp = callUpdate(rootUser, """{"webserver_auth_token":"new-token-123"}""")
        assertTrue(resp["success"]!!.jsonPrimitive.boolean)
        // 验证 DB 中已更新
        val config = appConfigDb.getConfig()
        assertEquals("new-token-123", config.webserverAuthToken)
        Unit
    }

    // WT4: 非 ROOT 更新 token → 权限不足
    @Test
    fun wt4_non_root_update_token() = runBlocking {
        val resp = callUpdate(tenantUser, """{"webserver_auth_token":"hacked"}""")
        assertEquals("权限不足", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // WT5: 请求参数无效
    @Test
    fun wt5_invalid_param() = runBlocking {
        val resp = callUpdate(rootUser, "not-json")
        assertEquals("请求参数无效", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // WT6: 更新后缓存同步
    @Test
    fun wt6_update_syncs_cache() = runBlocking {
        WebserverAuthTokenCache.init("old-cached")
        callUpdate(rootUser, """{"webserver_auth_token":"cache-synced"}""")
        // updateConfigPartial 自动 invalidate 缓存，下次 get() 从 DB 加载
        assertEquals("cache-synced", WebserverAuthTokenCache.get())
        Unit
    }
}
