package com.github.project_fredica.auth

import com.github.project_fredica.db.AppConfig
import com.github.project_fredica.db.AppConfigDb
import com.github.project_fredica.db.AppConfigService
import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthServiceTest {
    private lateinit var db: Database
    private lateinit var userDb: UserDb
    private lateinit var sessionDb: AuthSessionDb
    private lateinit var appConfigDb: AppConfigDb
    private lateinit var tmpFile: File

    private lateinit var testUserId: String
    private lateinit var testSession: AuthSessionEntity

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_authsvc_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        userDb = UserDb(db)
        sessionDb = AuthSessionDb(db)
        appConfigDb = AppConfigDb(db)
        runBlocking {
            userDb.initialize()
            sessionDb.initialize()
            appConfigDb.initialize()
            UserService.initialize(userDb)
            AuthSessionService.initialize(sessionDb)
            AppConfigService.initialize(appConfigDb)

            testUserId = userDb.createUser(
                username = "testuser",
                displayName = "Test User",
                passwordHash = "hash1",
                role = "tenant",
            )
            testSession = sessionDb.createSession(userId = testUserId)
        }
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    // R1: resolveIdentity — null/blank header → null
    @Test
    fun r1_resolveIdentity_null_header() = runBlocking {
        assertNull(AuthService.resolveIdentity(null))
        assertNull(AuthService.resolveIdentity(""))
        assertNull(AuthService.resolveIdentity("   "))
        Unit
    }

    // R2: resolveIdentity — 非 Bearer 前缀 → null
    @Test
    fun r2_resolveIdentity_non_bearer() = runBlocking {
        assertNull(AuthService.resolveIdentity("Basic abc123"))
        assertNull(AuthService.resolveIdentity("Token abc123"))
        Unit
    }

    // R3: resolveIdentity — Bearer + session token → AuthIdentity.TenantUser
    @Test
    fun r3_resolveIdentity_session_token() = runBlocking {
        val identity = AuthService.resolveIdentity("Bearer fredica_session:${testSession.token}")
        assertNotNull(identity)
        assertIs<AuthIdentity.TenantUser>(identity)
        assertEquals(testUserId, identity.userId)
        assertEquals("testuser", identity.username)
        assertEquals("Test User", identity.displayName)
        assertEquals(AuthRole.TENANT, identity.role)
        assertEquals(testSession.sessionId, identity.sessionId)
        Unit
    }

    // R4: resolveIdentity — Bearer + invalid session token → null
    @Test
    fun r4_resolveIdentity_invalid_session_token() = runBlocking {
        assertNull(AuthService.resolveIdentity("Bearer fredica_session:nonexistent_token"))
        Unit
    }

    // R5: resolveIdentity — Bearer + webserver_auth_token → Guest
    @Test
    fun r5_resolveIdentity_webserver_auth_token() = runBlocking {
        val config = appConfigDb.getConfig()
        appConfigDb.updateConfig(config.copy(webserverAuthToken = "test-webserver-token"))

        val identity = AuthService.resolveIdentity("Bearer test-webserver-token")
        assertNotNull(identity)
        assertIs<AuthIdentity.Guest>(identity)
        Unit
    }

    // R6: resolveIdentity — Bearer + unknown token → null
    @Test
    fun r6_resolveIdentity_unknown_token() = runBlocking {
        assertNull(AuthService.resolveIdentity("Bearer some-random-token"))
        Unit
    }

    // R7: resolveIdentity — disabled user → null
    @Test
    fun r7_resolveIdentity_disabled_user() = runBlocking {
        userDb.updateStatus(testUserId, "disabled")
        assertNull(AuthService.resolveIdentity("Bearer fredica_session:${testSession.token}"))
        Unit
    }

    // R8: resolveIdentity — expired session → null
    @Test
    fun r8_resolveIdentity_expired_session() = runBlocking {
        // 手动插入一个已过期的 session
        val nowSec = System.currentTimeMillis() / 1000L
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO auth_session (session_id, token, user_id, created_at, expires_at, last_accessed_at, user_agent, ip_address)
                VALUES (?, ?, ?, ?, ?, ?, '', '')
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, "expired-sid")
                ps.setString(2, "expired-token-value")
                ps.setString(3, testUserId)
                ps.setLong(4, nowSec - 86400 * 10)
                ps.setLong(5, nowSec - 1)  // 已过期
                ps.setLong(6, nowSec)
                ps.executeUpdate()
            }
        }

        assertNull(AuthService.resolveIdentity("Bearer fredica_session:expired-token-value"))
        Unit
    }

    // I1: isInstanceInitialized — 默认 false
    @Test
    fun i1_isInstanceInitialized_default_false() = runBlocking {
        assertFalse(AuthService.isInstanceInitialized())
        Unit
    }

    // I2: isInstanceInitialized — 设置后 true
    @Test
    fun i2_isInstanceInitialized_after_set() = runBlocking {
        val config = appConfigDb.getConfig()
        appConfigDb.updateConfig(config.copy(instanceInitialized = "true"))
        assertTrue(AuthService.isInstanceInitialized())
        Unit
    }

    // R9: resolveIdentity — root 用户角色正确解析
    @Test
    fun r9_resolveIdentity_root_role() = runBlocking {
        val rootId = userDb.createUser(
            username = "admin",
            displayName = "Admin",
            passwordHash = "hash2",
            role = "root",
        )
        val rootSession = sessionDb.createSession(userId = rootId)

        val identity = AuthService.resolveIdentity("Bearer fredica_session:${rootSession.token}")
        assertNotNull(identity)
        assertIs<AuthIdentity.RootUser>(identity)
        assertEquals(AuthRole.ROOT, identity.role)
        Unit
    }

    // R10: resolveIdentity — Bearer 后空 token → null
    @Test
    fun r10_resolveIdentity_empty_bearer_token() = runBlocking {
        assertNull(AuthService.resolveIdentity("Bearer "))
        assertNull(AuthService.resolveIdentity("Bearer fredica_session:"))
        Unit
    }
}
