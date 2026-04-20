package com.github.project_fredica.asr.service

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.PyCallGuard
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJson
import com.github.project_fredica.asr.model.BilibiliSubtitleBodyCache
import com.github.project_fredica.asr.db.BilibiliSubtitleBodyCacheRepo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Bilibili 字幕 body 缓存服务。
 *
 * 通过 [PyCallGuard] 两级锁保护，同一 urlKey 的并发请求只有第一个穿透到 Python，
 * 其余等锁后命中缓存。缓存写入 `bilibili_subtitle_body_cache` 表。
 */
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
    private suspend fun fetchOrLoad(
        urlKey: String,
        isUpdate: Boolean,
        loader: suspend () -> Pair<String, Boolean>,
    ): String {
        // Level 1：锁外快速命中
        if (!isUpdate) {
            val l1 = repo.queryBest(urlKey)
            l1?.let {
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
            repo.insert(
                BilibiliSubtitleBodyCache(
                    urlKey = urlKey,
                    queriedAt = System.currentTimeMillis() / 1000L,
                    rawResult = raw, isSuccess = isSuccess,
                )
            )
            logger.info("已写入缓存 urlKey=$urlKey is_success=$isSuccess raw_len=${raw.length}")
            raw
        }
    }

    suspend fun fetchBilibiliSubtitleBody(
        subtitleUrlFieldValue: String,
        isUpdate: Boolean,
    ): String {
        val urlKey = BilibiliSubtitleUtil.extractSubtitleUrlKey(subtitleUrlFieldValue)
        logger.debug("请求 urlKey=$urlKey is_update=${isUpdate} original_url=${subtitleUrlFieldValue}")
        val res = fetchOrLoad(urlKey, isUpdate) {
            val pyBody = buildJsonObject { put("subtitle_url", subtitleUrlFieldValue) }
            logger.debug("调用 Python /bilibili/video/subtitle-body urlKey=$urlKey")
            val row = FredicaApi.PyUtil.post("/bilibili/video/subtitle-body", pyBody.toString(), timeoutMs = 5 * 60_000L)
            logger.debug("Python 返回 urlKey=$urlKey raw_len=${row.length} raw_preview=${row.take(200)}")
            val errorCheck = runCatching {
                val obj = row.loadJson().getOrThrow() as? JsonObject
                obj?.get("error")?.let { (it as? JsonPrimitive)?.content }
            }.getOrNull()
            if (errorCheck != null) {
                logger.warn("Python 返回 error 字段 urlKey=$urlKey error=$errorCheck")
            }
            row to computeIsSuccess(row)
        }
        logger.debug("返回结果 urlKey=$urlKey")
        return res
    }

    private fun computeIsSuccess(raw: String): Boolean =
        runCatching {
            val obj = raw.loadJson().getOrThrow() as? JsonObject
            val code = (obj?.get("code") as? JsonPrimitive)?.content?.toIntOrNull()
            if (code != 0) {
                logger.debug("isSuccess=false: code=$code raw_preview=${raw.take(200)}")
            }
            code == 0
        }.getOrElse { e ->
            logger.warn("isSuccess 解析异常 raw_preview=${raw.take(200)}: ${e.message}")
            false
        }
}
