package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.db.BilibiliAiConclusionCache
import com.github.project_fredica.db.BilibiliAiConclusionCacheService
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object BilibiliVideoAiConclusionRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "获取 B 站视频 AI 总结"

    private val logger = createLogger { "BilibiliVideoAiConclusionRoute" }

    // 令牌桶：每 1.5 秒补充 1 令牌，最多积累 3 个（仅对 is_update=false 的自动请求生效）
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
            return if (tokens > 0) {
                tokens--; true
            } else false
        }
    }

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<BilibiliVideoAiConclusionParam>().getOrThrow()
        val cache = BilibiliAiConclusionCacheService.repo
        var waitedRateLimit = false

        if (!p.isUpdate) {
            val cached = cache.queryBest(p.bvid, p.pageIndex)
            if (cached != null) {
                logger.debug("命中缓存 bvid=${p.bvid} page=${p.pageIndex} is_success=${cached.isSuccess}")
                return ValidJsonString(cached.rawResult)
            }

            while (!rateLimiter.tryAcquire()) {
                if (!waitedRateLimit) {
                    logger.debug("令牌桶限速 bvid=${p.bvid} page=${p.pageIndex}")
                    waitedRateLimit = true
                }
                delay(1000)
            }
        }

        val cfg = AppConfigService.repo.getConfig()
        val pyBody = buildValidJson {
            kv("sessdata", cfg.bilibiliSessdata)
            kv("bili_jct", cfg.bilibiliBiliJct)
            kv("buvid3", cfg.bilibiliBuvid3)
            kv("buvid4", cfg.bilibiliBuvid4)
            kv("dedeuserid", cfg.bilibiliDedeuserid)
            kv("ac_time_value", cfg.bilibiliAcTimeValue)
            kv("proxy", cfg.bilibiliProxy)
        }
        val raw = FredicaApi.PyUtil.post("/bilibili/video/ai-conclusion/${p.bvid}/${p.pageIndex}", pyBody.str)
        val isSuccess = runCatching {
            val obj = raw.loadJson().getOrThrow() as? JsonObject
            val code = (obj?.get("code") as? JsonPrimitive)?.content?.toIntOrNull()
            val modelResult = obj?.get("model_result")
            code == 0 && modelResult != null && modelResult !is JsonNull
        }.getOrDefault(false)

        cache.insert(
            BilibiliAiConclusionCache(
                bvid = p.bvid,
                pageIndex = p.pageIndex,
                queriedAt = System.currentTimeMillis() / 1000L,
                rawResult = raw,
                isSuccess = isSuccess,
            )
        )
        logger.info("已写入缓存 bvid=${p.bvid} page=${p.pageIndex} is_success=$isSuccess")

        // 按优先级返回（可能有旧的成功记录比本次失败记录更好）
        val best = cache.queryBest(p.bvid, p.pageIndex)
        return ValidJsonString(best?.rawResult ?: raw)
    }
}

@Serializable
data class BilibiliVideoAiConclusionParam(
    val bvid: String,
    @SerialName("page_index") val pageIndex: Int = 0,
    @SerialName("is_update") val isUpdate: Boolean = false,
)
