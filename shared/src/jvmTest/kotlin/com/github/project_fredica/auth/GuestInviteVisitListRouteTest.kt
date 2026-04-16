package com.github.project_fredica.auth

import com.github.project_fredica.api.routes.GuestInviteVisitListRoute
import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.apputil.loadJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.ktorm.database.Database
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GuestInviteVisitListRoute handler 测试。
 * 需要真实 GuestInviteLinkDb + GuestInviteVisitDb。
 */
class GuestInviteVisitListRouteTest {
    private lateinit var db: Database
    private lateinit var linkDb: GuestInviteLinkDb
    private lateinit var visitDb: GuestInviteVisitDb
    private lateinit var tmpFile: File

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_guest_visit_list_", ".db").also { it.deleteOnExit() }
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

    // GET 路由 param 格式：Map<String, List<String>> JSON
    private suspend fun callVisitList(params: Map<String, List<String>>): JsonObject {
        val paramJson = kotlinx.serialization.json.buildJsonObject {
            params.forEach { (k, v) ->
                put(k, kotlinx.serialization.json.JsonArray(v.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }
        }.toString()
        val result = GuestInviteVisitListRoute.handler(paramJson, noContext)
        return result.str.loadJson().getOrThrow() as JsonObject
    }

    // GV1: 正常列出访问记录
    @Test
    fun gv1_list_visits() = runBlocking {
        val linkId = linkDb.create("link-visit", "Label", "admin")
        visitDb.record(linkId, "1.1.1.1", "Chrome")
        visitDb.record(linkId, "2.2.2.2", "Firefox")
        val resp = callVisitList(mapOf("link_id" to listOf(linkId)))
        assertEquals(2, resp["total"]!!.jsonPrimitive.int)
        assertEquals(2, resp["items"]!!.jsonArray.size)
        // 验证字段存在
        val item = resp["items"]!!.jsonArray[0] as JsonObject
        assertTrue(item.containsKey("id"))
        assertTrue(item.containsKey("link_id"))
        assertTrue(item.containsKey("ip_address"))
        assertTrue(item.containsKey("user_agent"))
        assertTrue(item.containsKey("visited_at"))
        Unit
    }

    // GV2: 空记录
    @Test
    fun gv2_list_empty() = runBlocking {
        val linkId = linkDb.create("link-empty", "Label", "admin")
        val resp = callVisitList(mapOf("link_id" to listOf(linkId)))
        assertEquals(0, resp["total"]!!.jsonPrimitive.int)
        assertEquals(0, resp["items"]!!.jsonArray.size)
        Unit
    }

    // GV3: 分页参数
    @Test
    fun gv3_pagination() = runBlocking {
        val linkId = linkDb.create("link-page", "Label", "admin")
        repeat(5) { visitDb.record(linkId, "1.1.1.$it", "UA") }
        val resp = callVisitList(mapOf(
            "link_id" to listOf(linkId),
            "limit" to listOf("2"),
            "offset" to listOf("1"),
        ))
        assertEquals(5, resp["total"]!!.jsonPrimitive.int)
        assertEquals(2, resp["items"]!!.jsonArray.size)
        Unit
    }

    // GV4: 缺少 link_id
    @Test
    fun gv4_missing_link_id() = runBlocking {
        val resp = callVisitList(mapOf("other" to listOf("value")))
        assertEquals("缺少 link_id", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // GV5: 请求参数无效
    @Test
    fun gv5_invalid_param() = runBlocking {
        val result = GuestInviteVisitListRoute.handler("not-json", noContext)
        val resp = result.str.loadJson().getOrThrow() as JsonObject
        assertEquals("请求参数无效", resp["error"]!!.jsonPrimitive.content)
        Unit
    }
}
