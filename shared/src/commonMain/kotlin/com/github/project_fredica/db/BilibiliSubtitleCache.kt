package com.github.project_fredica.db

import com.github.project_fredica.apputil.PyCallGuard
import com.github.project_fredica.apputil.createLogger
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

    private val logger = createLogger { "BilibiliSubtitleMetaCacheService" }
    private val guard = PyCallGuard()

    /**
     * 两级锁缓存加载：
     *   Level 1（锁外）：快速路径，无锁直接命中缓存即返回。
     *   Level 2（锁内）：double-check，防止前一个请求已写入缓存后重复调用 Python。
     * 同一 bvid:pageIndex 的并发请求只有第一个会穿透到 Python，其余等锁后命中缓存。
     */
    suspend fun fetchOrLoad(
        bvid: String,
        pageIndex: Int,
        isUpdate: Boolean,
        loader: suspend () -> Pair<String, Boolean>,
    ): String {
        // Level 1：锁外快速命中
        if (!isUpdate) {
            repo.queryBest(bvid, pageIndex)?.let {
                logger.debug("L1缓存命中 bvid=$bvid page=$pageIndex queried_at=${it.queriedAt}")
                return it.rawResult
            }
        }
        // 获取 per-resource 锁，串行化同一资源的并发请求
        logger.debug("等待资源锁 bvid=$bvid page=$pageIndex")
        return guard.withLock("$bvid:$pageIndex") {
            // Level 2：锁内 double-check（前一个请求可能已写入缓存）
            if (!isUpdate) {
                repo.queryBest(bvid, pageIndex)?.let {
                    logger.debug("L2缓存命中（锁内）bvid=$bvid page=$pageIndex queried_at=${it.queriedAt}")
                    return@withLock it.rawResult
                }
            }
            logger.debug("缓存未命中，调用 Python bvid=$bvid page=$pageIndex")
            val (raw, isSuccess) = loader()
            repo.insert(BilibiliSubtitleMetaCache(
                bvid = bvid, pageIndex = pageIndex,
                queriedAt = System.currentTimeMillis() / 1000L,
                rawResult = raw, isSuccess = isSuccess,
            ))
            logger.info("已写入缓存 bvid=$bvid page=$pageIndex is_success=$isSuccess")
            raw
        }
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

    private val logger = createLogger { "BilibiliSubtitleBodyCacheService" }
    private val guard = PyCallGuard()

    /**
     * 两级锁缓存加载：
     *   Level 1（锁外）：快速路径，无锁直接命中缓存即返回。
     *   Level 2（锁内）：double-check，防止前一个请求已写入缓存后重复调用 Python。
     * 同一 urlKey 的并发请求只有第一个会穿透到 Python，其余等锁后命中缓存。
     */
    suspend fun fetchOrLoad(
        urlKey: String,
        isUpdate: Boolean,
        loader: suspend () -> Pair<String, Boolean>,
    ): String {
        // Level 1：锁外快速命中
        if (!isUpdate) {
            repo.queryBest(urlKey)?.let {
                logger.debug("L1缓存命中 urlKey=$urlKey queried_at=${it.queriedAt}")
                return it.rawResult
            }
        }
        // 获取 per-resource 锁，串行化同一资源的并发请求
        logger.debug("等待资源锁 urlKey=$urlKey")
        return guard.withLock(urlKey) {
            // Level 2：锁内 double-check（前一个请求可能已写入缓存）
            if (!isUpdate) {
                repo.queryBest(urlKey)?.let {
                    logger.debug("L2缓存命中（锁内）urlKey=$urlKey queried_at=${it.queriedAt}")
                    return@withLock it.rawResult
                }
            }
            logger.debug("缓存未命中，调用 Python urlKey=$urlKey")
            val (raw, isSuccess) = loader()
            repo.insert(BilibiliSubtitleBodyCache(
                urlKey = urlKey,
                queriedAt = System.currentTimeMillis() / 1000L,
                rawResult = raw, isSuccess = isSuccess,
            ))
            logger.info("已写入缓存 urlKey=$urlKey is_success=$isSuccess raw_len=${raw.length}")
            raw
        }
    }
}
