package com.github.project_fredica.auth

import com.github.project_fredica.api.routes.GuestInviteLinkDeleteRoute
import com.github.project_fredica.api.routes.GuestInviteLinkListRoute
import com.github.project_fredica.api.routes.GuestInviteLinkUpdateRoute
import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.apputil.loadJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.ktorm.database.Database
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GuestInviteLinkListRoute / GuestInviteLinkDeleteRoute / GuestInviteLinkUpdateRoute handler 测试。
 * 需要真实 GuestInviteLinkDb + GuestInviteVisitDb。
 */
class GuestInviteLinkManagementRouteTest {
    private lateinit var db: Database
    private lateinit var linkDb: GuestInviteLinkDb
    private lateinit var visitDb: GuestInviteVisitDb
    private lateinit var tmpFile: File

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_guest_link_mgmt_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        linkDb = GuestInviteLinkDb(db)
        visitDb = GuestInviteVisitDb(db)
        runBlocking {
            linkDb.initialize()
            visitDb.initialize()
        }
        GuestInviteLinkService.initialize(linkDb)
        GuestInviteVisitService.initialize(visitDb)
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    private val noContext = RouteContext(identity = null, clientIp = null, userAgent = null)

    private suspend fun callList(): JsonArray {
        val result = GuestInviteLinkListRoute.handler("", noContext)
        return result.str.loadJson().getOrThrow() as JsonArray
    }

    private suspend fun callDelete(param: String): JsonObject {
        val result = GuestInviteLinkDeleteRoute.handler(param, noContext)
        return result.str.loadJson().getOrThrow() as JsonObject
    }

    private suspend fun callUpdate(param: String): JsonObject {
        val result = GuestInviteLinkUpdateRoute.handler(param, noContext)
        return result.str.loadJson().getOrThrow() as JsonObject
    }

    // ==================== GuestInviteLinkListRoute ====================

    // GL1: 空列表
    @Test
    fun gl1_list_empty() = runBlocking {
        val arr = callList()
        assertEquals(0, arr.size)
        Unit
    }

    // GL2: 列出多个链接
    @Test
    fun gl2_list_multiple() = runBlocking {
        linkDb.create("link-aaa", "Label A", "admin")
        linkDb.create("link-bbb", "Label B", "admin")
        val arr = callList()
        assertEquals(2, arr.size)
        Unit
    }

    // GL3: 列表包含 visit_count 聚合
    @Test
    fun gl3_list_includes_visit_count() = runBlocking {
        val linkId = linkDb.create("link-ccc", "Label C", "admin")
        visitDb.record(linkId, "1.1.1.1", "UA1")
        visitDb.record(linkId, "2.2.2.2", "UA2")
        val arr = callList()
        assertEquals(1, arr.size)
        val link = arr[0] as JsonObject
        assertEquals(2, link["visit_count"]!!.jsonPrimitive.content.toInt())
        Unit
    }

    // ==================== GuestInviteLinkDeleteRoute ====================

    // GL4: 删除成功
    @Test
    fun gl4_delete_success() = runBlocking {
        val linkId = linkDb.create("link-del", "To Delete", "admin")
        val resp = callDelete("""{"id":"$linkId"}""")
        assertTrue(resp["success"]!!.jsonPrimitive.boolean)
        // 验证已删除
        assertNull(linkDb.findById(linkId))
        Unit
    }

    // GL5: 删除级联删除访问记录
    @Test
    fun gl5_delete_cascades_visits() = runBlocking {
        val linkId = linkDb.create("link-cas", "Cascade", "admin")
        visitDb.record(linkId, "1.1.1.1", "UA")
        visitDb.record(linkId, "2.2.2.2", "UA")
        assertEquals(2, visitDb.countByLinkId(linkId))
        callDelete("""{"id":"$linkId"}""")
        assertEquals(0, visitDb.countByLinkId(linkId))
        Unit
    }

    // GL6: 链接不存在
    @Test
    fun gl6_delete_not_found() = runBlocking {
        val resp = callDelete("""{"id":"nonexistent"}""")
        assertEquals("链接不存在", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // GL7: 请求参数无效
    @Test
    fun gl7_delete_invalid_param() = runBlocking {
        val resp = callDelete("not-json")
        assertEquals("请求参数无效", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // ==================== GuestInviteLinkUpdateRoute ====================

    // GL8: 更新 label
    @Test
    fun gl8_update_label() = runBlocking {
        val linkId = linkDb.create("link-upd", "Old Label", "admin")
        val resp = callUpdate("""{"id":"$linkId","label":"New Label"}""")
        assertTrue(resp["success"]!!.jsonPrimitive.boolean)
        assertEquals("New Label", linkDb.findById(linkId)!!.label)
        Unit
    }

    // GL9: 更新 status
    @Test
    fun gl9_update_status() = runBlocking {
        val linkId = linkDb.create("link-sts", "Label", "admin")
        val resp = callUpdate("""{"id":"$linkId","status":"disabled"}""")
        assertTrue(resp["success"]!!.jsonPrimitive.boolean)
        assertEquals("disabled", linkDb.findById(linkId)!!.status)
        Unit
    }

    // GL10: 同时更新 label 和 status
    @Test
    fun gl10_update_both() = runBlocking {
        val linkId = linkDb.create("link-both", "Old", "admin")
        val resp = callUpdate("""{"id":"$linkId","label":"New","status":"disabled"}""")
        assertTrue(resp["success"]!!.jsonPrimitive.boolean)
        val link = linkDb.findById(linkId)!!
        assertEquals("New", link.label)
        assertEquals("disabled", link.status)
        Unit
    }

    // GL11: 链接不存在
    @Test
    fun gl11_update_not_found() = runBlocking {
        val resp = callUpdate("""{"id":"nonexistent","label":"X"}""")
        assertEquals("链接不存在", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // GL12: 请求参数无效
    @Test
    fun gl12_update_invalid_param() = runBlocking {
        val resp = callUpdate("not-json")
        assertEquals("请求参数无效", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // GL13: 无效 status 值
    @Test
    fun gl13_update_invalid_status() = runBlocking {
        val linkId = linkDb.create("link-inv", "Label", "admin")
        val resp = callUpdate("""{"id":"$linkId","status":"invalid"}""")
        // updateStatus 会抛 require 异常，被 try/catch 捕获
        assertTrue(resp.containsKey("error"))
        Unit
    }
}
