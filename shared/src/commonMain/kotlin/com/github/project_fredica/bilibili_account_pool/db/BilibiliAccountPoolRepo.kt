package com.github.project_fredica.bilibili_account_pool.db

import com.github.project_fredica.bilibili_account_pool.model.BilibiliAccount

interface BilibiliAccountPoolRepo {
    suspend fun getAll(): List<BilibiliAccount>
    suspend fun getById(id: String): BilibiliAccount?
    suspend fun getDefault(): BilibiliAccount?
    suspend fun upsertAll(accounts: List<BilibiliAccount>)
    suspend fun deleteById(id: String)
    suspend fun deleteAll()
    suspend fun updateIpCheckResult(id: String, ip: String, checkedAt: Long)
}
