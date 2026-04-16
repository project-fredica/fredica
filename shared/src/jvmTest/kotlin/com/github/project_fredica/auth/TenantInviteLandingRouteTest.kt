package com.github.project_fredica.auth

import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.api.routes.TenantInviteLandingRoute
import com.github.project_fredica.apputil.loadJson
import kotlinx.coroutines.runBlocking
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TenantInviteLandingRouteTest {
    private lateinit var db: Database
    private lateinit var linkDb: TenantInviteLinkDb
    private lateinit var regDb: TenantInviteRegistrationDb
    private lateinit var userDb: UserDb
    private lateinit var tmpFile: File

    private val futureExpiry: String get() = Instant.now().plus(7, ChronoUnit.DAYS).toString()
    private val pastExpiry: String get() = Instant.now().minus(1, ChronoUnit.DAYS).toString()

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_tenant_landing_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        linkDb = TenantInviteLinkDb(db)
        regDb = TenantInviteRegistrationDb(db)
        userDb = UserDb(db)
        runBlocking {
            userDb.initialize()
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

    private fun queryParam(pathId: String): String =
        """{"path_id":["$pathId"]}"""

    private suspend fun callHandler(pathId: String): JsonObject {
        val result = TenantInviteLandingRoute.handler(queryParam(pathId), noContext)
        return result.str.loadJson().getOrThrow() as JsonObject
    }

    // C-2.1: 可用链接 → { usable: true, label: "..." }
    @Test
    fun c2_1_usable_link_returns_usable_true() = runBlocking {
        linkDb.create("tenant-ok", "租户链接", 10, futureExpiry, "admin-1")

        val resp = callHandler("tenant-ok")
        assertTrue(resp["usable"]!!.jsonPrimitive.boolean)
        assertEquals("租户链接", resp["label"]!!.jsonPrimitive.content)
        Unit
    }

    // C-2.2: 已禁用链接 → { usable: false, reason: "disabled" }
    @Test
    fun c2_2_disabled_link_returns_disabled() = runBlocking {
        val id = linkDb.create("tenant-dis", "", 10, futureExpiry, "admin-1")
        linkDb.updateStatus(id, "disabled")

        val resp = callHandler("tenant-dis")
        assertFalse(resp["usable"]!!.jsonPrimitive.boolean)
        assertEquals("disabled", resp["reason"]!!.jsonPrimitive.content)
        Unit
    }

    // C-2.3: 已过期链接 → { usable: false, reason: "expired" }
    @Test
    fun c2_3_expired_link_returns_expired() = runBlocking {
        // 直接插入已过期记录（绕过 create 的 require 检查）
        insertLinkDirectly("tenant-exp", "active", 10, pastExpiry)

        val resp = callHandler("tenant-exp")
        assertFalse(resp["usable"]!!.jsonPrimitive.boolean)
        assertEquals("expired", resp["reason"]!!.jsonPrimitive.content)
        Unit
    }

    // C-2.4: 名额已满 → { usable: false, reason: "full" }
    @Test
    fun c2_4_full_link_returns_full() = runBlocking {
        val id = linkDb.create("tenant-full", "", 2, futureExpiry, "admin-1")
        // 注册 2 个用户填满名额
        val userId1 = userDb.createUser("fill-user-1", "U1", "hash")
        val userId2 = userDb.createUser("fill-user-2", "U2", "hash")
        regDb.record(id, userId1, "1.1.1.1", "UA")
        regDb.record(id, userId2, "1.1.1.1", "UA")

        val resp = callHandler("tenant-full")
        assertFalse(resp["usable"]!!.jsonPrimitive.boolean)
        assertEquals("full", resp["reason"]!!.jsonPrimitive.content)
        Unit
    }

    // 额外：不存在的 pathId → error
    @Test
    fun nonexistent_pathId_returns_error() = runBlocking {
        val resp = callHandler("no-such-tenant")
        assertEquals("链接不存在", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    // 额外：缺少 path_id 参数
    @Test
    fun missing_pathId_returns_error() = runBlocking {
        val result = TenantInviteLandingRoute.handler("""{}""", noContext)
        val resp = result.str.loadJson().getOrThrow() as JsonObject
        assertEquals("缺少 path_id", resp["error"]!!.jsonPrimitive.content)
        Unit
    }

    /** 直接插入一条链接记录，绕过 create 的 require 检查（用于测试已过期场景） */
    private suspend fun insertLinkDirectly(pathId: String, status: String, maxUses: Int, expiresAt: String) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO tenant_invite_link (id, path_id, label, status, max_uses, expires_at, created_by, created_at, updated_at)
                    VALUES (?, ?, '', ?, ?, ?, 'admin-1', ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    val id = java.util.UUID.randomUUID().toString()
                    val now = java.time.Instant.now().toString()
                    ps.setString(1, id)
                    ps.setString(2, pathId)
                    ps.setString(3, status)
                    ps.setInt(4, maxUses)
                    ps.setString(5, expiresAt)
                    ps.setString(6, now)
                    ps.setString(7, now)
                    ps.executeUpdate()
                }
            }
        }
    }
}
