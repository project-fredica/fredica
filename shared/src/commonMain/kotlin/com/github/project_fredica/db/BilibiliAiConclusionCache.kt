package com.github.project_fredica.db

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BilibiliAiConclusionCache(
    val id: Long = 0,
    val bvid: String,
    @SerialName("page_index") val pageIndex: Int = 0,
    @SerialName("queried_at") val queriedAt: Long,
    @SerialName("raw_result") val rawResult: String,
    @SerialName("is_success") val isSuccess: Boolean,
)

interface BilibiliAiConclusionCacheRepo {
    suspend fun insert(entry: BilibiliAiConclusionCache)

    /** 优先返回最新成功记录，无则返回最新失败记录，无则 null */
    suspend fun queryBest(
        bvid: String,
        pageIndex: Int,
        expireTime: Long = 86400L
    ): BilibiliAiConclusionCache?
}

object BilibiliAiConclusionCacheService {
    private var _repo: BilibiliAiConclusionCacheRepo? = null
    val repo: BilibiliAiConclusionCacheRepo
        get() = _repo ?: error("BilibiliAiConclusionCacheService 未初始化")

    fun initialize(repo: BilibiliAiConclusionCacheRepo) {
        _repo = repo
    }
}
