package com.github.project_fredica.asr.service

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.PyCallGuard
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJson
import com.github.project_fredica.asr.model.BilibiliSubtitleMetaCache
import com.github.project_fredica.asr.db.BilibiliSubtitleMetaCacheRepo
import com.github.project_fredica.bilibili_account_pool.model.BilibiliAccount
import com.github.project_fredica.bilibili_account_pool.service.BilibiliAccountPoolService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Bilibili 字幕元信息缓存服务。
 *
 * 通过 [PyCallGuard] 两级锁保护，同一 bvid:pageIndex 的并发请求只有第一个穿透到 Python，
 * 其余等锁后命中缓存。缓存写入 `bilibili_subtitle_meta_cache` 表。
 *
 * isSuccess 判定包含三重校验（code=0 + subtitles 非 null + 无凭据时空列表视为失败），
 * 防止把"未登录时空字幕"误缓存为成功。
 */
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
     * 避免在 BilibiliVideoSubtitleRoute 中重复构建凭据和 isSuccess 判定逻辑。
     *
     * @param bvid      Bilibili BV 号
     * @param pageIndex 分P下标（0-based）
     * @param isUpdate  是否强制刷新缓存
     * @param cfg       BilibiliAccount（含凭据 + 代理配置）
     */
    suspend fun fetchBilibiliSubtitleMeta(
        bvid: String,
        pageIndex: Int,
        isUpdate: Boolean,
        cfg: BilibiliAccount,
    ): String = fetchOrLoad(bvid, pageIndex, isUpdate) {
        val hasCreds = cfg.bilibiliSessdata.isNotBlank()
        val credBody = BilibiliAccountPoolService.buildPyCredentialBody(cfg)
        logger.debug("调用 Python bvid=${bvid} pageIndex=${pageIndex} hasCreds=$hasCreds proxy=${credBody["proxy"]}")
        val raw = FredicaApi.PyUtil.post(
            "/bilibili/video/subtitle-meta/$bvid/$pageIndex", credBody.toString(), timeoutMs = 5 * 60_000L
        )
        logger.debug("Python 返回 bvid=${bvid} pageIndex=${pageIndex} raw_len=${raw.length} raw_preview=${raw.take(200)}")
        // 检查 Python 层是否返回了 error 字段（子进程超时、异常退出等）
        val errorCheck = runCatching {
            val obj = raw.loadJson().getOrThrow() as? JsonObject
            obj?.get("error")?.let { (it as? JsonPrimitive)?.content }
        }.getOrNull()
        if (errorCheck != null) {
            logger.warn("Python 返回 error 字段 bvid=$bvid pageIndex=$pageIndex error=$errorCheck")
        }
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
        if (code != 0) {
            logger.debug("isSuccess=false bvid=$bvid: code=$code (非 0)")
            return@runCatching false
        }

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
    }.getOrElse { e ->
        logger.warn("isSuccess 解析异常 bvid=$bvid raw_preview=${raw.take(200)}: ${e.message}")
        false
    }
}
