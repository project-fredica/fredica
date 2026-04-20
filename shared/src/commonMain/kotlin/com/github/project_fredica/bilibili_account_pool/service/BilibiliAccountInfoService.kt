package com.github.project_fredica.bilibili_account_pool.service

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.asT
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJson
import com.github.project_fredica.bilibili_account_pool.db.BilibiliAccountInfoRepo
import com.github.project_fredica.bilibili_account_pool.model.BilibiliAccount
import com.github.project_fredica.bilibili_account_pool.model.BilibiliAccountInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object BilibiliAccountInfoService {
    private var _repo: BilibiliAccountInfoRepo? = null
    val repo: BilibiliAccountInfoRepo
        get() = _repo ?: error("BilibiliAccountInfoService 未初始化")

    fun initialize(repo: BilibiliAccountInfoRepo) {
        _repo = repo
    }

    private val logger = createLogger { "BilibiliAccountInfoService" }

    suspend fun fetchAndSave(account: BilibiliAccount): BilibiliAccountInfo {
        val credBody = BilibiliAccountPoolService.buildPyCredentialBody(account)
        logger.debug("获取账号信息 accountId=${account.id} dedeuserid=${account.bilibiliDedeuserid}")
        val raw = FredicaApi.PyUtil.post(
            "/bilibili/credential/get-account-info",
            credBody.toString(), timeoutMs = 30_000L
        )
        val json = raw.loadJson().getOrThrow() as JsonObject
        val errorMsg = json["error"]?.asT<String>()?.getOrNull()
        if (errorMsg != null) error(errorMsg)
        val info = BilibiliAccountInfo(
            accountId = account.id,
            mid = json["mid"]?.asT<String>()?.getOrNull() ?: account.bilibiliDedeuserid,
            name = json["name"]?.asT<String>()?.getOrNull() ?: "",
            face = json["face"]?.asT<String>()?.getOrNull() ?: "",
            level = (json["level"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            sign = json["sign"]?.asT<String>()?.getOrNull() ?: "",
            coins = (json["coins"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0,
            fans = (json["fans"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            following = (json["following"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            queriedAt = System.currentTimeMillis() / 1000L,
        )
        repo.upsert(info)
        logger.info("已保存账号信息 accountId=${account.id} name=${info.name}")
        return info
    }
}
