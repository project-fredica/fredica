package com.github.project_fredica.auth

import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.api.routes.TenantInviteRegistrationListRoute
import com.github.project_fredica.apputil.loadJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.ktorm.database.Database
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TenantInviteRegistrationListRoute handler 测试。
 * 需要真实 TenantInviteLinkDb + TenantInviteRegistrationDb + UserDb（LEFT JOIN）。
 */
class TenantInviteRegistrationListRouteTest {
    private lateinit var db: Database
    private lateinit var linkDb: TenantInviteLinkDb
    private lateinit var regDb: TenantInviteRegistrationDb
    private lateinit var userDb: UserDb
    private lateinit var tmpFile: File

    private val futureExpiry: String
        get() = Instant.now().plus(7, ChronoUnit.DAYS).toString()

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_tenant_reg_list_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        linkDb = TenantInviteLinkDb(db)
        regDb = TenantInviteRegistrationDb(db)
        userDb = UserDb(db)
        runBlocking {
            linkDb.initialize()
            regDb.initialize()
            userDb.initialize()
        }
        TenantInviteLinkService.initialize(linkDb)
        TenantInviteRegistrationService.initialize(regDb)
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    private val defaultContext = RouteContext(identity = null, clientIp = null, userAgent = null)

    // GET 路由 param 格式：Map<String, List<String>> JSON
    private suspend fun callRegList(params: Map<String, List<String>>): JsonObject {
        val paramJson = kotlinx.serialization.json.buildJsonObject {
            params.forEach { (k, v) ->
                put(k, kotlinx.serialization.json.JsonArray(v.map { JsonPrimitive(it) }))
            }
        }.toString()
        val result = TenantInviteRegistrationListRoute.handler(paramJson, defaultContext)
        return result.str.loadJson().getOrThrow() as JsonObject
    }

    // TR1: 正常列出注册记录
    @Test
    fun tr1_list_registrations() = runBlocking {
        val linkId = linkDb.create("tenant-reg-list", "Label", 10, futureExpiry, "admin")
        regDb.record(linkId, "user-1", "1.1.1.1", "Chrome")
        regDb.record(linkId, "user-2", "2.2.2.2", "Firefox")
        val resp = callRegList(mapOf("link_id" to listOf(linkId)))
        assertEquals(2, resp["total"]!!.jsonPrimitive.int)
        assertEquals(2, resp["items"]!!.jsonArray.size)
        // 验证字段存在
        val item = resp["items"]!!.jsonArray[0] as JsonObject
        assertTrue(item.containsKey("id"))
        assertTrue(item.containsKey("link_id"))
        assertTrue(item.containsKey("user_id"))
        assertTrue(item.containsKey("ip_address"))
        assertTrue(item.containsKey("user_agent"))
        assertTrue(item.containsKey("registered_at"))
        Unit
    }

    // TR2: 空记录
    @Test
    fun tr2_list_empty() = runBlocking {
        val linkId = linkDb.create("tenant-reg-empty", "Label", 10, futureExpiry, "admin")
        val resp = callRegList(mapOf("link_id" to listOf(linkId)))
        assertEquals(0, resp["total"]!!.jsonPrimitive.int)
        assertEquals(0, resp["items"]!!.jsonArray.size)
        Unit
    }

    // TR3: 包含用户信息（LEFT JOIN user 表）
    @Test
    fun tr3_includes_user_info() = runBlocking {
        val userId = userDb.createUser("testuser", "Test User", "hash123")
        val linkId = linkDb.create("tenant-reg-user", "Label", 10, futureExpiry, "admin")
        regDb.record(linkId, userId, "1.1.1.1", "Chrome")
        val resp = callRegList(mapOf("link_id" to listOf(linkId)))
        assertEquals(1, resp["total"]!!.jsonPrimitive.int)
        val item = resp["items"]!!.jsonArray[0] as JsonObject
        assertEquals("testuser", item["username"]!!.jsonPrimitive.content)
        assertEquals("Test User", item["display_name"]!!.jsonPrimitive.content)
        Unit
    }

    // TR4: 用户不存在时 username/display_name 为 null（不含在 JSON 中）
    @Test
    fun tr4_missing_user_no_username() = runBlocking {
        val linkId = linkDb.create("tenant-reg-nouser", "Label", 10, futureExpiry, "admin")
        regDb.record(linkId, "nonexistent-user", "1.1.1.1", "Chrome")
        val resp = callRegList(mapOf("link_id" to listOf(linkId)))
        assertEquals(1, resp["total"]!!.jsonPrimitive.int)
        val item = resp["items"]!!.jsonArray[0] as JsonObject
        // LEFT JOIN 未匹配时，username 和 display_name 不应出现在 JSON 中
        assertTrue(!item.containsKey("username") || item["username"] is kotlinx.serialization.json.JsonNull)
        assertTrue(!item.containsKey("display_name") || item["display_name"] is kotlinx.serialization.json.JsonNull)
        Unit
    }

    // TR5: 缺少 link_id
    @Test
    fun tr5_missing_link_id() = runBlocking {
        val resp = callRegList(mapOf("other" to listOf("value")))
        assertEquals("缺少 link_id", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // TR6: 请求参数无效
    @Test
    fun tr6_invalid_param() = runBlocking {
        val result = TenantInviteRegistrationListRoute.handler("not-json", defaultContext)
        val resp = result.str.loadJson().getOrThrow() as JsonObject
        assertEquals("请求参数无效", resp["error"]!!.jsonPrimitive.content)
        Unit
    }
}
