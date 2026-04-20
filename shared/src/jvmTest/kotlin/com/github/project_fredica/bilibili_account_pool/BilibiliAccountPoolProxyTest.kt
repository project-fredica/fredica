package com.github.project_fredica.bilibili_account_pool

// =============================================================================
// BilibiliAccountPoolProxyTest —— 代理解析与凭据构建 单元测试
// =============================================================================
//
// 被测对象：BilibiliAccountPoolService.resolveProxy / buildPyCredentialBody
//
// 代理来源规则：
//   - ""         → 直连（不使用代理）
//   - "USE_APP"  → 跟随应用代理（AppConfig.proxyUrl > 系统代理）
//   - 其他字符串 → 自定义代理地址，原样传递给 Python
//
// 测试矩阵：
//   resolveProxy:
//     1. empty → ""
//     2. custom http → 原样返回
//     3. custom socks5 → 原样返回
//     4. USE_APP + AppConfig 有值 → 返回 AppConfig.proxyUrl
//     5. USE_APP + AppConfig 为空 → 回落系统代理（测试环境通常为空）
//   buildPyCredentialBody:
//     6. USE_APP → body.proxy = 解析后的应用代理，含全部凭据字段
//     7. custom → body.proxy = 原样
//     8. empty → body.proxy = ""
//   端到端（DB 持久化 → 读取 → 解析）:
//     9.  USE_APP 持久化后 resolveProxy 正确
//    10.  custom 持久化后 resolveProxy 正确
//    11.  empty 持久化后 resolveProxy 正确
//    12.  修改 AppConfig.proxyUrl 后 USE_APP 账号动态跟随新值
//
// 测试环境：每个测试用例独立的 SQLite 临时文件，同时初始化 AppConfig 和 AccountPool 两张表。
// =============================================================================

import com.github.project_fredica.bilibili_account_pool.db.BilibiliAccountPoolDb
import com.github.project_fredica.bilibili_account_pool.model.BilibiliAccount
import com.github.project_fredica.bilibili_account_pool.service.BilibiliAccountPoolService
import com.github.project_fredica.db.AppConfigDb
import com.github.project_fredica.db.AppConfigService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.ktorm.database.Database
import java.io.File
import kotlin.test.*

class BilibiliAccountPoolProxyTest {
    private lateinit var db: Database
    private lateinit var tmpFile: File
    private lateinit var poolDb: BilibiliAccountPoolDb

    private val now = System.currentTimeMillis() / 1000L

    @BeforeTest
    fun setup() = runBlocking {
        tmpFile = File.createTempFile("test_proxy_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")

        val appConfigDb = AppConfigDb(db)
        appConfigDb.initialize()
        AppConfigService.initialize(appConfigDb)

        poolDb = BilibiliAccountPoolDb(db)
        poolDb.initialize()
        BilibiliAccountPoolService.initialize(poolDb)
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    private fun makeAccount(
        id: String = "acct-1",
        proxy: String = "",
        impersonate: String = "chrome",
        sessdata: String = "sess_abc",
    ) = BilibiliAccount(
        id = id,
        label = "测试",
        bilibiliSessdata = sessdata,
        bilibiliBiliJct = "jct_abc",
        bilibiliBuvid3 = "buvid3_abc",
        bilibiliBuvid4 = "buvid4_abc",
        bilibiliDedeuserid = "uid_123",
        bilibiliAcTimeValue = "ac_abc",
        bilibiliProxy = proxy,
        bilibiliImpersonate = impersonate,
        createdAt = now,
        updatedAt = now,
    )

    // ── resolveProxy 测试 ────────────────────────────────────────────────────

    // 1. proxy="" → 直连，resolveProxy 原样返回空串
    @Test
    fun resolveProxy_empty_returns_empty() = runBlocking {
        val acct = makeAccount(proxy = "")
        assertEquals("", BilibiliAccountPoolService.resolveProxy(acct))
        Unit
    }

    // 2. 自定义 HTTP 代理 → 原样返回
    @Test
    fun resolveProxy_custom_returns_as_is() = runBlocking {
        val acct = makeAccount(proxy = "http://my-proxy:8080")
        assertEquals("http://my-proxy:8080", BilibiliAccountPoolService.resolveProxy(acct))
        Unit
    }

    // 3. 自定义 SOCKS5 代理 → 原样返回（验证协议不限于 http）
    @Test
    fun resolveProxy_socks5_returns_as_is() = runBlocking {
        val acct = makeAccount(proxy = "socks5://127.0.0.1:1080")
        assertEquals("socks5://127.0.0.1:1080", BilibiliAccountPoolService.resolveProxy(acct))
        Unit
    }

    // 4. USE_APP + AppConfig.proxyUrl 有值 → 返回应用级代理
    @Test
    fun resolveProxy_USE_APP_reads_app_config_proxy() = runBlocking {
        val config = AppConfigService.repo.getConfig()
        AppConfigService.repo.updateConfig(config.copy(proxyUrl = "http://app-proxy:7890"))

        val acct = makeAccount(proxy = "USE_APP")
        assertEquals("http://app-proxy:7890", BilibiliAccountPoolService.resolveProxy(acct))
        Unit
    }

    // 5. USE_APP + AppConfig.proxyUrl 为空 → 回落到系统代理（CI 环境通常为空串）
    @Test
    fun resolveProxy_USE_APP_empty_app_config_falls_back_to_system() = runBlocking {
        val config = AppConfigService.repo.getConfig()
        AppConfigService.repo.updateConfig(config.copy(proxyUrl = ""))

        val acct = makeAccount(proxy = "USE_APP")
        val result = BilibiliAccountPoolService.resolveProxy(acct)
        // 系统代理在 CI/测试环境通常为空，但不应抛异常
        assertNotNull(result)
        Unit
    }

    // ── buildPyCredentialBody 测试 ───────────────────────────────────────────

    // 6. USE_APP → body 中 proxy 为解析后的应用代理，且包含全部凭据字段
    @Test
    fun buildPyCredentialBody_includes_resolved_proxy() = runBlocking {
        val config = AppConfigService.repo.getConfig()
        AppConfigService.repo.updateConfig(config.copy(proxyUrl = "http://app-proxy:7890"))

        val acct = makeAccount(proxy = "USE_APP", impersonate = "firefox")
        val body = BilibiliAccountPoolService.buildPyCredentialBody(acct)

        assertEquals("http://app-proxy:7890", body["proxy"]?.jsonPrimitive?.content)
        assertEquals("firefox", body["impersonate"]?.jsonPrimitive?.content)
        assertEquals("sess_abc", body["sessdata"]?.jsonPrimitive?.content)
        assertEquals("jct_abc", body["bili_jct"]?.jsonPrimitive?.content)
        assertEquals("buvid3_abc", body["buvid3"]?.jsonPrimitive?.content)
        assertEquals("buvid4_abc", body["buvid4"]?.jsonPrimitive?.content)
        assertEquals("uid_123", body["dedeuserid"]?.jsonPrimitive?.content)
        assertEquals("ac_abc", body["ac_time_value"]?.jsonPrimitive?.content)
        Unit
    }

    // 7. 自定义代理 → body.proxy 原样传递
    @Test
    fun buildPyCredentialBody_custom_proxy_passed_directly() = runBlocking {
        val acct = makeAccount(proxy = "socks5://custom:1080", impersonate = "chrome")
        val body = BilibiliAccountPoolService.buildPyCredentialBody(acct)

        assertEquals("socks5://custom:1080", body["proxy"]?.jsonPrimitive?.content)
        assertEquals("chrome", body["impersonate"]?.jsonPrimitive?.content)
        Unit
    }

    // 8. 空代理 → body.proxy = ""，表示直连
    @Test
    fun buildPyCredentialBody_empty_proxy_means_direct() = runBlocking {
        val acct = makeAccount(proxy = "")
        val body = BilibiliAccountPoolService.buildPyCredentialBody(acct)

        assertEquals("", body["proxy"]?.jsonPrimitive?.content)
        Unit
    }

    // ── 端到端：保存 → 读取 → resolveProxy ──────────────────────────────────

    // 9. USE_APP 写入 DB 后原样保存为 "USE_APP"，resolveProxy 解析为应用代理
    @Test
    fun proxy_roundtrip_USE_APP_persisted_and_resolved() = runBlocking {
        val config = AppConfigService.repo.getConfig()
        AppConfigService.repo.updateConfig(config.copy(proxyUrl = "http://saved-proxy:3128"))

        val acct = makeAccount(id = "rt-1", proxy = "USE_APP")
        poolDb.upsertAll(listOf(acct))

        val loaded = BilibiliAccountPoolService.repo.getById("rt-1")!!
        assertEquals("USE_APP", loaded.bilibiliProxy)

        val resolved = BilibiliAccountPoolService.resolveProxy(loaded)
        assertEquals("http://saved-proxy:3128", resolved)
        Unit
    }

    // 10. 自定义代理写入 DB 后原样保存，resolveProxy 原样返回
    @Test
    fun proxy_roundtrip_custom_persisted_and_resolved() = runBlocking {
        val acct = makeAccount(id = "rt-2", proxy = "http://custom:8080")
        poolDb.upsertAll(listOf(acct))

        val loaded = BilibiliAccountPoolService.repo.getById("rt-2")!!
        assertEquals("http://custom:8080", loaded.bilibiliProxy)

        val resolved = BilibiliAccountPoolService.resolveProxy(loaded)
        assertEquals("http://custom:8080", resolved)
        Unit
    }

    // 11. 空代理写入 DB 后原样保存，resolveProxy 返回空串
    @Test
    fun proxy_roundtrip_empty_persisted_and_resolved() = runBlocking {
        val acct = makeAccount(id = "rt-3", proxy = "")
        poolDb.upsertAll(listOf(acct))

        val loaded = BilibiliAccountPoolService.repo.getById("rt-3")!!
        assertEquals("", loaded.bilibiliProxy)

        val resolved = BilibiliAccountPoolService.resolveProxy(loaded)
        assertEquals("", resolved)
        Unit
    }

    // 12. 修改 AppConfig.proxyUrl 后，USE_APP 账号的 resolveProxy 动态跟随新值
    @Test
    fun proxy_update_changes_resolution() = runBlocking {
        val config = AppConfigService.repo.getConfig()
        AppConfigService.repo.updateConfig(config.copy(proxyUrl = "http://old-proxy:1234"))

        val acct = makeAccount(id = "upd-1", proxy = "USE_APP")
        poolDb.upsertAll(listOf(acct))

        assertEquals("http://old-proxy:1234", BilibiliAccountPoolService.resolveProxy(
            BilibiliAccountPoolService.repo.getById("upd-1")!!
        ))

        // 更新 app 代理配置
        AppConfigService.repo.updateConfig(
            AppConfigService.repo.getConfig().copy(proxyUrl = "http://new-proxy:5678")
        )

        assertEquals("http://new-proxy:5678", BilibiliAccountPoolService.resolveProxy(
            BilibiliAccountPoolService.repo.getById("upd-1")!!
        ))
        Unit
    }
}
