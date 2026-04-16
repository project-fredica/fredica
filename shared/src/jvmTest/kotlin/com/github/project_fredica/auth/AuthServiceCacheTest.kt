package com.github.project_fredica.auth

import com.github.project_fredica.db.AppConfigDb
import com.github.project_fredica.db.AppConfigService
import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * AuthService.resolveIdentity() 与 WebserverAuthTokenCache 的集成测试。
 * 验证缓存命中、缓存失效后 lazy reload、以及空缓存行为。
 */
class AuthServiceCacheTest {
    private lateinit var db: Database
    private lateinit var userDb: UserDb
    private lateinit var sessionDb: AuthSessionDb
    private lateinit var appConfigDb: AppConfigDb
    private lateinit var tmpFile: File

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_authcache_", ".db").also { it.deleteOnExit() }
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
        }
        WebserverAuthTokenCache.invalidate()
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    // IC1: 缓存命中 — 有效游客令牌 → Guest
    @Test
    fun ic1_cache_hit_valid_guest_token() = runBlocking {
        appConfigDb.updateConfigPartial(mapOf("webserver_auth_token" to "guest-token-1"))
        // 预热缓存
        WebserverAuthTokenCache.init("guest-token-1")

        val identity = AuthService.resolveIdentity("Bearer guest-token-1")
        assertNotNull(identity)
        assertIs<AuthIdentity.Guest>(identity)
        Unit
    }

    // IC2: 缓存命中 — 无效令牌 → null
    @Test
    fun ic2_cache_hit_invalid_token() = runBlocking {
        WebserverAuthTokenCache.init("real-token")

        val identity = AuthService.resolveIdentity("Bearer wrong-token")
        assertNull(identity)
        Unit
    }

    // IC3: 缓存失效后 lazy reload — 更新 DB 中的 token 后旧 token 失效
    @Test
    fun ic3_invalidation_old_token_fails() = runBlocking {
        // 设置初始 token
        appConfigDb.updateConfigPartial(mapOf("webserver_auth_token" to "old-token"))

        val identity1 = AuthService.resolveIdentity("Bearer old-token")
        assertNotNull(identity1)
        assertIs<AuthIdentity.Guest>(identity1)

        // 更新 token（updateConfigPartial 自动 invalidate 缓存）
        appConfigDb.updateConfigPartial(mapOf("webserver_auth_token" to "new-token"))

        // 旧 token 不再匹配
        assertNull(AuthService.resolveIdentity("Bearer old-token"))
        // 新 token 匹配（lazy reload 从 DB 加载）
        val identity2 = AuthService.resolveIdentity("Bearer new-token")
        assertNotNull(identity2)
        assertIs<AuthIdentity.Guest>(identity2)
        Unit
    }

    // IC4: 空缓存时跳过游客检查
    @Test
    fun ic4_empty_cache_skips_guest_check() = runBlocking {
        // DB 中 webserver_auth_token 默认为空字符串
        // 任何 token 都不应匹配 Guest
        assertNull(AuthService.resolveIdentity("Bearer some-random-token"))
        Unit
    }

    // IC5: updateConfig 触发缓存失效后 resolveIdentity 正确工作
    @Test
    fun ic5_updateConfig_invalidates_cache() = runBlocking {
        WebserverAuthTokenCache.init("cached-value")

        // 通过 updateConfig 更新（非 partial）
        val config = appConfigDb.getConfig()
        appConfigDb.updateConfig(config.copy(webserverAuthToken = "full-update-token"))

        // 缓存已失效，resolveIdentity 应 lazy reload 并匹配新 token
        val identity = AuthService.resolveIdentity("Bearer full-update-token")
        assertNotNull(identity)
        assertIs<AuthIdentity.Guest>(identity)
        Unit
    }
}
