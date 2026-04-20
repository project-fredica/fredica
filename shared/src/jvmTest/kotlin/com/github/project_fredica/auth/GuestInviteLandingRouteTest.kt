package com.github.project_fredica.auth

import com.github.project_fredica.api.routes.GuestInviteLandingRoute
import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.apputil.loadJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.ktorm.database.Database
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GuestInviteLandingRouteTest {
    private lateinit var db: Database
    private lateinit var linkDb: GuestInviteLinkDb
    private lateinit var visitDb: GuestInviteVisitDb
    private lateinit var tmpFile: File

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_guest_landing_", ".db").also { it.deleteOnExit() }
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

    private fun queryParam(pathId: String): String =
        buildJsonObject {
            put("path_id", buildJsonArray { add(JsonPrimitive(pathId)) })
        }.toString()

    private suspend fun callHandler(pathId: String, ip: String = "1.1.1.1", ua: String = "TestAgent"): JsonObject {
        val ctx = RouteContext(identity = null, clientIp = ip, userAgent = ua)
        val result = GuestInviteLandingRoute.handler(queryParam(pathId), ctx)
        return result.str.loadJson().getOrThrow() as JsonObject
    }

    // C-1.1: 有效 pathId → success + visit 记录 +1
    @Test
    fun c1_1_valid_pathId_returns_success_and_records_visit() = runBlocking {
        val linkId = linkDb.create("guest-test-1", "测试链接", "admin-1")

        val resp = callHandler("guest-test-1")
        assertTrue(resp["success"]!!.jsonPrimitive.boolean)
        assertEquals("测试链接", resp["label"]!!.jsonPrimitive.content)

        // 验证访问记录
        val visits = visitDb.listByLinkId(linkId)
        assertEquals(1, visits.size)
        assertEquals("1.1.1.1", visits[0].ipAddress)
        assertEquals("TestAgent", visits[0].userAgent)
        Unit
    }

    // C-1.2: 不存在的 pathId → error
    @Test
    fun c1_2_nonexistent_pathId_returns_error() = runBlocking {
        val resp = callHandler("no-such-path")
        assertEquals("链接不存在", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // C-1.3: 已禁用的链接 → error
    @Test
    fun c1_3_disabled_link_returns_error() = runBlocking {
        val linkId = linkDb.create("guest-disabled", "禁用链接", "admin-1")
        linkDb.updateStatus(linkId, "disabled")

        val resp = callHandler("guest-disabled")
        assertEquals("链接已禁用", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // C-1.4: 同一 IP 10 次访问 → 10 条记录（不去重）
    @Test
    fun c1_4_same_ip_10_visits_records_all() = runBlocking {
        val linkId = linkDb.create("guest-multi", "多次访问", "admin-1")

        repeat(10) {
            callHandler("guest-multi", ip = "2.2.2.2", ua = "Bot")
        }

        val visits = visitDb.listByLinkId(linkId)
        assertEquals(10, visits.size)
        visits.forEach {
            assertEquals("2.2.2.2", it.ipAddress)
            assertEquals("Bot", it.userAgent)
        }
        Unit
    }

    // 额外：缺少 path_id 参数
    @Test
    fun missing_pathId_returns_error() = runBlocking {
        val ctx = RouteContext(identity = null, clientIp = "1.1.1.1", userAgent = "TestAgent")
        val result = GuestInviteLandingRoute.handler("""{}""", ctx)
        val resp = result.str.loadJson().getOrThrow() as JsonObject
        assertNotNull(resp["error"])
        Unit
    }
}
