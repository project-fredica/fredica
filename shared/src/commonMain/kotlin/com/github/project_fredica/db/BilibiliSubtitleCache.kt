package com.github.project_fredica.db

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.api.routes.BilibiliVideoSubtitleBodyRoute
import com.github.project_fredica.api.routes.BilibiliVideoSubtitleRoute
import com.github.project_fredica.apputil.BilibiliApiPythonCredentialConfig
import com.github.project_fredica.apputil.PyCallGuard
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    private suspend fun fetchOrLoad(
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
            repo.insert(
                BilibiliSubtitleMetaCache(
                    bvid = bvid, pageIndex = pageIndex,
                    queriedAt = System.currentTimeMillis() / 1000L,
                    rawResult = raw, isSuccess = isSuccess,
                )
            )
            logger.info("已写入缓存 bvid=$bvid page=$pageIndex is_success=$isSuccess")
            raw
        }
    }

    /**
     * 通过缓存+锁保护获取 Bilibili 字幕元信息。
     *
     * 封装 [BilibiliSubtitleMetaCacheService.fetchOrLoad] 的 loader 参数，
     * 避免在 [BilibiliVideoSubtitleRoute] 和 [FetchSubtitleExecutor] 中重复构建凭据和 isSuccess 判定逻辑。
     *
     * @param bvid      Bilibili BV 号
     * @param pageIndex 分P下标（0-based）
     * @param isUpdate  是否强制刷新缓存
     * @param cfg       AppConfig（含 Bilibili 凭据）
     */
    suspend fun fetchBilibiliSubtitleMeta(
        bvid: String,
        pageIndex: Int,
        isUpdate: Boolean,
        cfg: BilibiliApiPythonCredentialConfig,
    ): String = fetchOrLoad(bvid, pageIndex, isUpdate) {
        val hasCreds = cfg.bilibiliSessdata.isNotBlank()
        val credBody = buildValidJson {
            kv("sessdata", cfg.bilibiliSessdata)
            kv("bili_jct", cfg.bilibiliBiliJct)
            kv("buvid3", cfg.bilibiliBuvid3)
            kv("buvid4", cfg.bilibiliBuvid4)
            kv("dedeuserid", cfg.bilibiliDedeuserid)
            kv("ac_time_value", cfg.bilibiliAcTimeValue)
            kv("proxy", cfg.bilibiliProxy)
        }
        logger.debug("调用 Python bvid=${bvid} pageIndex=${pageIndex}")
        val raw = FredicaApi.PyUtil.post(
            "/bilibili/video/subtitle-meta/$bvid/$pageIndex", credBody.str, timeoutMs = 5 * 60_000L
        )
        val isSuccess = computeIsSuccess(raw, bvid, hasCreds)
        raw to isSuccess
    }

    // ── isSuccess 判定（三重校验，防止把"未登录时空字幕"误缓存为成功）─────────────
    //
    // B站 API 在 Session 失效或未登录时不会抛错，而是静默返回：
    //   {"code": 0, "subtitles": []}
    // 即 code=0 但字幕列表为空——AI 字幕（ai-zh）对未登录用户不可见。
    //
    // 判定规则：
    //   1. code 必须为 0
    //   2. subtitles 字段不得为 null（API 异常时 Python 返回 "subtitles": null）
    //   3. 若 subtitles 为空列表（[]）且当前请求无凭据 → isSuccess=false
    //      若携带凭据返回空列表 → isSuccess=true（该视频确实没有字幕）。
    private fun computeIsSuccess(raw: String, bvid: String, hasCreds: Boolean): Boolean = runCatching {
        val obj = raw.loadJson().getOrThrow() as? JsonObject
        val code = (obj?.get("code") as? JsonPrimitive)?.content?.toIntOrNull()
        if (code != 0) return@runCatching false

        val subtitlesElem = obj["subtitles"]
        if (subtitlesElem == null || subtitlesElem is JsonNull) {
            logger.warn("isSuccess=false bvid=$bvid: code=0 但 subtitles 为 null，视为查询失败")
            return@runCatching false
        }

        val subtitlesArr = subtitlesElem as? JsonArray
        val isEmpty = subtitlesArr == null || subtitlesArr.isEmpty()
        if (isEmpty && !hasCreds) {
            logger.warn(
                "isSuccess=false bvid=$bvid: 无登录凭据且字幕列表为空，可能是 Session 失效导致 AI 字幕不可见，不作为成功结果缓存"
            )
            return@runCatching false
        }

        true
    }.getOrDefault(false)
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
    private suspend fun fetchOrLoad(
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
        val urlKey = extractUrlKey(subtitleUrlFieldValue)
        logger.debug("请求 urlKey=$urlKey is_update=${isUpdate} original_url=${subtitleUrlFieldValue}")
        val res = fetchOrLoad(urlKey, isUpdate) {
            val pyBody = buildValidJson { kv("subtitle_url", subtitleUrlFieldValue) }
            logger.debug("调用 Python /bilibili/video/subtitle-body urlKey=$urlKey")
            val row = FredicaApi.PyUtil.post("/bilibili/video/subtitle-body", pyBody.str, timeoutMs = 5 * 60_000L)
            row to computeIsSuccess(row)
        }
        logger.debug("返回结果 urlKey=$urlKey")
        return res
    }

    private fun computeIsSuccess(raw: String): Boolean =
        runCatching {
            val obj = raw.loadJson().getOrThrow() as? JsonObject
            val code = (obj?.get("code") as? JsonPrimitive)?.content?.toIntOrNull()
            code == 0
        }.getOrDefault(false)

    /** 去掉 auth_key / wts 等会过期的 query 参数，保留稳定的路径+其余参数作为缓存 key */
    private fun extractUrlKey(url: String): String {
        val normalized = if (url.startsWith("//")) "https:$url" else url
        return try {
            val uri = java.net.URI(normalized)
            val stableParams = (uri.query ?: "")
                .split("&")
                .filter { part ->
                    val key = part.substringBefore("=")
                    key !in setOf("auth_key", "wts", "w_rid", "e")
                }
                .joinToString("&")
            "${uri.host}${uri.path}${if (stableParams.isNotEmpty()) "?$stableParams" else ""}"
        } catch (_: Exception) {
            normalized
        }
    }
}
