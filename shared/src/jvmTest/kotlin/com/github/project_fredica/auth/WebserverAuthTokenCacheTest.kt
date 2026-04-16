package com.github.project_fredica.auth

import com.github.project_fredica.db.AppConfigDb
import com.github.project_fredica.db.AppConfigService
import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WebserverAuthTokenCacheTest {
    private lateinit var db: Database
    private lateinit var appConfigDb: AppConfigDb
    private lateinit var tmpFile: File

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_wscache_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        appConfigDb = AppConfigDb(db)
        runBlocking {
            appConfigDb.initialize()
            AppConfigService.initialize(appConfigDb)
        }
        // 每个测试前清除缓存状态
        WebserverAuthTokenCache.invalidate()
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    // C1: 未 init 时 get() 从 DB 加载（默认空字符串）
    @Test
    fun c1_get_without_init_loads_from_db() = runBlocking {
        assertEquals("", WebserverAuthTokenCache.get())
        Unit
    }

    // C2: init 后 get() 返回预热值
    @Test
    fun c2_init_then_get() = runBlocking {
        WebserverAuthTokenCache.init("preheated-token")
        assertEquals("preheated-token", WebserverAuthTokenCache.get())
        Unit
    }

    // C3: invalidate 后 get() 重新从 DB 加载
    @Test
    fun c3_invalidate_reloads_from_db() = runBlocking {
        WebserverAuthTokenCache.init("old-token")
        // 直接更新 DB 中的 token
        appConfigDb.updateConfigPartial(mapOf("webserver_auth_token" to "new-db-token"))
        // updateConfigPartial 已自动调用 invalidate()
        // 下次 get() 应从 DB 重新加载
        assertEquals("new-db-token", WebserverAuthTokenCache.get())
        Unit
    }

    // C4: updateConfig 触发 invalidate
    @Test
    fun c4_updateConfig_triggers_invalidate() = runBlocking {
        WebserverAuthTokenCache.init("cached-token")
        val config = appConfigDb.getConfig()
        appConfigDb.updateConfig(config.copy(webserverAuthToken = "updated-via-config"))
        assertEquals("updated-via-config", WebserverAuthTokenCache.get())
        Unit
    }

    // C5: updateConfigPartial 仅在包含 webserver_auth_token 时 invalidate
    @Test
    fun c5_updateConfigPartial_only_invalidates_on_relevant_key() = runBlocking {
        WebserverAuthTokenCache.init("should-stay")
        // 更新不相关的键
        appConfigDb.updateConfigPartial(mapOf("theme" to "dark"))
        // 缓存应保持不变
        assertEquals("should-stay", WebserverAuthTokenCache.get())
        Unit
    }

    // C6: 多次 init 取最后值
    @Test
    fun c6_multiple_init_takes_last() = runBlocking {
        WebserverAuthTokenCache.init("first")
        WebserverAuthTokenCache.init("second")
        WebserverAuthTokenCache.init("third")
        assertEquals("third", WebserverAuthTokenCache.get())
        Unit
    }

    // C7: init 空字符串
    @Test
    fun c7_init_empty_string() = runBlocking {
        WebserverAuthTokenCache.init("")
        assertEquals("", WebserverAuthTokenCache.get())
        Unit
    }
}
