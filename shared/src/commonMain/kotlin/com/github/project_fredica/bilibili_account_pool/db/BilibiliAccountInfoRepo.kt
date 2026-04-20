package com.github.project_fredica.bilibili_account_pool.db

import com.github.project_fredica.bilibili_account_pool.model.BilibiliAccountInfo

interface BilibiliAccountInfoRepo {
    suspend fun upsert(info: BilibiliAccountInfo)
    suspend fun getByAccountId(accountId: String): BilibiliAccountInfo?
    suspend fun getAll(): Map<String, BilibiliAccountInfo>
    suspend fun deleteByAccountId(accountId: String)
}
