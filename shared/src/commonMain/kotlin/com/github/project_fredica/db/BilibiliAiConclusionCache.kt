package com.github.project_fredica.db

import com.github.project_fredica.apputil.PyCallGuard
import com.github.project_fredica.apputil.createLogger
import kotlinx.coroutines.delay
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

    private val logger = createLogger { "BilibiliAiConclusionCacheService" }
    private val guard = PyCallGuard()

    // 令牌桶：每 1.5 秒补充 1 令牌，最多积累 3 个
    // 与 per-resource 锁配合：锁保证串行化，令牌桶保证全局调用频率不超限
    private val rateLimiter = object {
        private val maxTokens = 3
        private var tokens = maxTokens
        private var lastRefill = System.currentTimeMillis()
        private val refillIntervalMs = 1_500L

        @Synchronized
        fun tryAcquire(): Boolean {
            val now = System.currentTimeMillis()
            val refilled = ((now - lastRefill) / refillIntervalMs).toInt()
            if (refilled > 0) {
                tokens = minOf(maxTokens, tokens + refilled)
                lastRefill += refilled * refillIntervalMs
            }
            return if (tokens > 0) { tokens--; true } else false
        }
    }

    /**
     * 两级锁缓存加载（含令牌桶限速）：
     *   Level 1（锁外）：快速路径，无锁直接命中缓存即返回。
     *   Level 2（锁内）：double-check + 令牌桶限速 + Python 调用。
     * 同一 bvid:pageIndex 的并发请求只有第一个会穿透到 Python，其余等锁后命中缓存。
     * 失败时优先返回历史成功记录（queryBest 语义）。
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
                logger.debug("L1缓存命中 bvid=$bvid page=$pageIndex is_success=${it.isSuccess}")
                return it.rawResult
            }
        }
        // 获取 per-resource 锁，串行化同一资源的并发请求
        logger.debug("等待资源锁 bvid=$bvid page=$pageIndex")
        return guard.withLock("$bvid:$pageIndex") {
            // Level 2：锁内 double-check（前一个请求可能已写入缓存）
            if (!isUpdate) {
                repo.queryBest(bvid, pageIndex)?.let {
                    logger.debug("L2缓存命中（锁内）bvid=$bvid page=$pageIndex is_success=${it.isSuccess}")
                    return@withLock it.rawResult
                }
            }
            // 令牌桶限速：B站 AI 总结接口有频率限制，避免短时间内大量请求
            var waited = false
            while (!rateLimiter.tryAcquire()) {
                if (!waited) {
                    logger.debug("令牌桶限速，等待 bvid=$bvid page=$pageIndex")
                    waited = true
                }
                delay(1000)
            }
            logger.debug("缓存未命中，调用 Python bvid=$bvid page=$pageIndex")
            val (raw, isSuccess) = loader()
            repo.insert(BilibiliAiConclusionCache(
                bvid = bvid, pageIndex = pageIndex,
                queriedAt = System.currentTimeMillis() / 1000L,
                rawResult = raw, isSuccess = isSuccess,
            ))
            logger.info("已写入缓存 bvid=$bvid page=$pageIndex is_success=$isSuccess")
            // 本次失败时，优先返回历史成功记录（queryBest 按 is_success 降序）
            if (!isSuccess) {
                val best = repo.queryBest(bvid, pageIndex)?.rawResult
                if (best != null && best != raw) {
                    logger.debug("本次失败，返回历史成功记录 bvid=$bvid page=$pageIndex")
                }
                best ?: raw
            } else raw
        }
    }
}
