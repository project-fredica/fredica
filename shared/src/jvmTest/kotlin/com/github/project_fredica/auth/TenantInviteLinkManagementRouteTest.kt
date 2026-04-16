package com.github.project_fredica.auth

import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.api.routes.TenantInviteLinkDeleteRoute
import com.github.project_fredica.api.routes.TenantInviteLinkListRoute
import com.github.project_fredica.api.routes.TenantInviteLinkUpdateRoute
import com.github.project_fredica.apputil.loadJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.ktorm.database.Database
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TenantInviteLinkListRoute / TenantInviteLinkDeleteRoute / TenantInviteLinkUpdateRoute handler 测试。
 * 需要真实 TenantInviteLinkDb + TenantInviteRegistrationDb。
 */
class TenantInviteLinkManagementRouteTest {
    private lateinit var db: Database
    private lateinit var linkDb: TenantInviteLinkDb
    private lateinit var regDb: TenantInviteRegistrationDb
    private lateinit var tmpFile: File

    private val futureExpiry: String
        get() = Instant.now().plus(7, ChronoUnit.DAYS).toString()

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_tenant_link_mgmt_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        linkDb = TenantInviteLinkDb(db)
        regDb = TenantInviteRegistrationDb(db)
        runBlocking {
            linkDb.initialize()
            regDb.initialize()
        }
        TenantInviteLinkService.initialize(linkDb)
        TenantInviteRegistrationService.initialize(regDb)
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    private val noContext = RouteContext(identity = null, clientIp = null, userAgent = null)

    private suspend fun callList(): JsonArray {
        val result = TenantInviteLinkListRoute.handler("", noContext)
        return result.str.loadJson().getOrThrow() as JsonArray
    }

    private suspend fun callDelete(param: String): JsonObject {
        val result = TenantInviteLinkDeleteRoute.handler(param, noContext)
        return result.str.loadJson().getOrThrow() as JsonObject
    }

    private suspend fun callUpdate(param: String): JsonObject {
        val result = TenantInviteLinkUpdateRoute.handler(param, noContext)
        return result.str.loadJson().getOrThrow() as JsonObject
    }

    // ==================== TenantInviteLinkListRoute ====================

    // TL1: 空列表
    @Test
    fun tl1_list_empty() = runBlocking {
        val arr = callList()
        assertEquals(0, arr.size)
        Unit
    }

    // TL2: 列出多个链接
    @Test
    fun tl2_list_multiple() = runBlocking {
        linkDb.create("tenant-aaa", "Label A", 10, futureExpiry, "admin")
        linkDb.create("tenant-bbb", "Label B", 5, futureExpiry, "admin")
        val arr = callList()
        assertEquals(2, arr.size)
        Unit
    }

    // TL3: 列表包含 used_count 聚合
    @Test
    fun tl3_list_includes_used_count() = runBlocking {
        val linkId = linkDb.create("tenant-ccc", "Label C", 10, futureExpiry, "admin")
        regDb.record(linkId, "user-1", "1.1.1.1", "UA")
        regDb.record(linkId, "user-2", "2.2.2.2", "UA")
        val arr = callList()
        assertEquals(1, arr.size)
        val link = arr[0] as JsonObject
        assertEquals(2, link["used_count"]!!.jsonPrimitive.content.toInt())
        Unit
    }

    // ==================== TenantInviteLinkDeleteRoute ====================

    // TL4: 删除成功（无注册记录）
    @Test
    fun tl4_delete_success() = runBlocking {
        val linkId = linkDb.create("tenant-del", "To Delete", 10, futureExpiry, "admin")
        val resp = callDelete("""{"id":"$linkId"}""")
        assertTrue(resp["success"]!!.jsonPrimitive.boolean)
        assertNull(linkDb.findById(linkId))
        Unit
    }

    // TL5: 有注册记录时删除失败
    @Test
    fun tl5_delete_with_registrations() = runBlocking {
        val linkId = linkDb.create("tenant-reg", "Has Regs", 10, futureExpiry, "admin")
        regDb.record(linkId, "user-1", "1.1.1.1", "UA")
        val resp = callDelete("""{"id":"$linkId"}""")
        assertTrue(resp.containsKey("error"))
        assertTrue(resp["error"]!!.jsonPrimitive.content.contains("注册记录"))
        // 链接仍存在
        assertTrue(linkDb.findById(linkId) != null)
        Unit
    }

    // TL6: 链接不存在
    @Test
    fun tl6_delete_not_found() = runBlocking {
        val resp = callDelete("""{"id":"nonexistent"}""")
        assertEquals("链接不存在", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // TL7: 请求参数无效
    @Test
    fun tl7_delete_invalid_param() = runBlocking {
        val resp = callDelete("not-json")
        assertEquals("请求参数无效", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // ==================== TenantInviteLinkUpdateRoute ====================

    // TL8: 更新 label
    @Test
    fun tl8_update_label() = runBlocking {
        val linkId = linkDb.create("tenant-upd", "Old Label", 10, futureExpiry, "admin")
        val resp = callUpdate("""{"id":"$linkId","label":"New Label"}""")
        assertTrue(resp["success"]!!.jsonPrimitive.boolean)
        assertEquals("New Label", linkDb.findById(linkId)!!.label)
        Unit
    }

    // TL9: 更新 status
    @Test
    fun tl9_update_status() = runBlocking {
        val linkId = linkDb.create("tenant-sts", "Label", 10, futureExpiry, "admin")
        val resp = callUpdate("""{"id":"$linkId","status":"disabled"}""")
        assertTrue(resp["success"]!!.jsonPrimitive.boolean)
        assertEquals("disabled", linkDb.findById(linkId)!!.status)
        Unit
    }

    // TL10: 链接不存在
    @Test
    fun tl10_update_not_found() = runBlocking {
        val resp = callUpdate("""{"id":"nonexistent","label":"X"}""")
        assertEquals("链接不存在", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // TL11: 请求参数无效
    @Test
    fun tl11_update_invalid_param() = runBlocking {
        val resp = callUpdate("not-json")
        assertEquals("请求参数无效", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // TL12: 无效 status 值
    @Test
    fun tl12_update_invalid_status() = runBlocking {
        val linkId = linkDb.create("tenant-inv", "Label", 10, futureExpiry, "admin")
        val resp = callUpdate("""{"id":"$linkId","status":"invalid"}""")
        assertTrue(resp.containsKey("error"))
        Unit
    }
}
