package com.github.project_fredica.db

import kotlinx.serialization.Serializable

@Serializable
data class LlmResponseCache(
    val id: Long = 0,
    val keyHash: String,
    val cacheKey: String,
    val modelName: String,
    val baseUrl: String,
    val messagesJson: String,          // 规范化后的 messages JSON
    val responseText: String,
    val isValid: Boolean = true,
    val createdAt: Long,
    val lastHitAt: Long,
    val hitCount: Int = 0,
)

interface LlmResponseCacheRepo {
    /** 按 key_hash 查询，无效（is_valid=0）视为未命中 */
    suspend fun findByHash(keyHash: String): LlmResponseCache?
    /** INSERT OR REPLACE（以 key_hash UNIQUE 约束保证幂等） */
    suspend fun upsert(entry: LlmResponseCache)
    /** 更新命中统计 */
    suspend fun updateHit(keyHash: String, hitAt: Long)
    /** 废除缓存：is_valid = 0，不物理删除 */
    suspend fun invalidate(keyHash: String)
    /** 按 model_name + base_url 批量废除（模型配置变更时使用） */
    suspend fun invalidateByModel(modelName: String, baseUrl: String)
}

object LlmResponseCacheService {
    private var _repo: LlmResponseCacheRepo? = null
    val repo get() = _repo ?: error("LlmResponseCacheService 未初始化")
    fun initialize(repo: LlmResponseCacheRepo) { _repo = repo }
}
