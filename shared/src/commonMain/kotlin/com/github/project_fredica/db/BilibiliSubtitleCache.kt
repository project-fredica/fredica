package com.github.project_fredica.db

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 字幕元信息缓存（subtitle_meta，不含字幕内容） */
@Serializable
data class BilibiliSubtitleMetaCache(
    val id: Long = 0,
    val bvid: String,
    @SerialName("page_index") val pageIndex: Int = 0,
    @SerialName("queried_at") val queriedAt: Long,
    @SerialName("raw_result") val rawResult: String,
    @SerialName("is_success") val isSuccess: Boolean,
)

interface BilibiliSubtitleMetaCacheRepo {
    suspend fun insert(entry: BilibiliSubtitleMetaCache)
    suspend fun queryBest(bvid: String, pageIndex: Int): BilibiliSubtitleMetaCache?
}

object BilibiliSubtitleMetaCacheService {
    private var _repo: BilibiliSubtitleMetaCacheRepo? = null
    val repo: BilibiliSubtitleMetaCacheRepo
        get() = _repo ?: error("BilibiliSubtitleMetaCacheService 未初始化")

    fun initialize(repo: BilibiliSubtitleMetaCacheRepo) {
        _repo = repo
    }
}

/** 字幕内容缓存（单条字幕 body，按 url_key 索引） */
@Serializable
data class BilibiliSubtitleBodyCache(
    val id: Long = 0,
    @SerialName("url_key") val urlKey: String,
    @SerialName("queried_at") val queriedAt: Long,
    @SerialName("raw_result") val rawResult: String,
    @SerialName("is_success") val isSuccess: Boolean,
)

interface BilibiliSubtitleBodyCacheRepo {
    suspend fun insert(entry: BilibiliSubtitleBodyCache)
    suspend fun queryBest(urlKey: String): BilibiliSubtitleBodyCache?
}

object BilibiliSubtitleBodyCacheService {
    private var _repo: BilibiliSubtitleBodyCacheRepo? = null
    val repo: BilibiliSubtitleBodyCacheRepo
        get() = _repo ?: error("BilibiliSubtitleBodyCacheService 未初始化")

    fun initialize(repo: BilibiliSubtitleBodyCacheRepo) {
        _repo = repo
    }
}
