package com.github.project_fredica.bilibili_account_pool.service

import com.github.project_fredica.apputil.AppProxyService
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.bilibili_account_pool.db.BilibiliAccountPoolRepo
import com.github.project_fredica.bilibili_account_pool.model.BilibiliAccount
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object BilibiliAccountPoolService {
    private val logger = createLogger()
    private var _repo: BilibiliAccountPoolRepo? = null

    val repo: BilibiliAccountPoolRepo
        get() = _repo ?: throw IllegalStateException("BilibiliAccountPoolService not initialized")

    fun initialize(repo: BilibiliAccountPoolRepo) {
        _repo = repo
    }

    private const val PROXY_USE_APP = "USE_APP"

    /**
     * 解析账号的代理地址。
     * - `"USE_APP"` → 跟随应用代理（AppProxyService，优先 App 配置 > 系统代理）
     * - `""` → 直连（不使用代理）
     * - 其他 → 自定义代理地址，原样返回
     */
    suspend fun resolveProxy(account: BilibiliAccount): String {
        val raw = account.bilibiliProxy
        if (raw == PROXY_USE_APP) {
            val resolved = AppProxyService.readProxyUrl()
            logger.debug("resolveProxy: account=${account.id} USE_APP → '$resolved'")
            return resolved
        }
        logger.debug("resolveProxy: account=${account.id} raw='$raw'")
        return raw
    }

    suspend fun getDefaultCredentialConfig(): BilibiliAccount? {
        return repo.getDefault()
    }

    suspend fun buildSyncAccountList(): JsonArray {
        val accounts = repo.getAll()
        val anon = accounts.count { it.isAnonymous || it.bilibiliSessdata.isBlank() }
        logger.debug("buildSyncAccountList: total=${accounts.size} anonymous=$anon logged_in=${accounts.size - anon}")
        return buildJsonArray {
            for ((idx, acct) in accounts.withIndex()) {
                add(buildJsonObject {
                    put("label", acct.label.ifBlank { "账号${idx + 1}" })
                    if (acct.isAnonymous || acct.bilibiliSessdata.isBlank()) {
                        put("credential", null as String?)
                    } else {
                        put("credential", buildJsonObject {
                            put("sessdata", acct.bilibiliSessdata)
                            put("bili_jct", acct.bilibiliBiliJct)
                            put("buvid3", acct.bilibiliBuvid3)
                            put("buvid4", acct.bilibiliBuvid4)
                            put("dedeuserid", acct.bilibiliDedeuserid)
                            put("ac_time_value", acct.bilibiliAcTimeValue)
                        })
                    }
                    put("proxy", resolveProxy(acct))
                    put("impersonate", acct.bilibiliImpersonate)
                    put("rate_limit_sec", acct.rateLimitSec)
                })
            }
        }
    }

    suspend fun buildPyCredentialBody(account: BilibiliAccount) = buildJsonObject {
        val resolved = resolveProxy(account)
        logger.debug("buildPyCredentialBody: account=${account.id} proxy='$resolved'")
        put("sessdata", account.bilibiliSessdata)
        put("bili_jct", account.bilibiliBiliJct)
        put("buvid3", account.bilibiliBuvid3)
        put("buvid4", account.bilibiliBuvid4)
        put("dedeuserid", account.bilibiliDedeuserid)
        put("ac_time_value", account.bilibiliAcTimeValue)
        put("proxy", resolved)
        put("impersonate", account.bilibiliImpersonate)
    }
}
