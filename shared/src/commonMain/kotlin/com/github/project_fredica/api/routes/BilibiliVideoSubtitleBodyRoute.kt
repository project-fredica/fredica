package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.BilibiliSubtitleBodyCache
import com.github.project_fredica.db.BilibiliSubtitleBodyCacheService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object BilibiliVideoSubtitleBodyRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "获取 B 站视频字幕内容"

    private val logger = createLogger { "BilibiliVideoSubtitleBodyRoute" }

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<BilibiliVideoSubtitleBodyParam>().getOrThrow()
        val cache = BilibiliSubtitleBodyCacheService.repo

        val urlKey = extractUrlKey(p.subtitleUrl)
        logger.debug("请求 urlKey=$urlKey is_update=${p.isUpdate} original_url=${p.subtitleUrl}")

        if (!p.isUpdate) {
            val cached = cache.queryBest(urlKey)
            if (cached != null) {
                logger.debug("命中缓存 urlKey=$urlKey queried_at=${cached.queriedAt}")
                return ValidJsonString(cached.rawResult)
            }
            logger.debug("无缓存，发起 Python 请求 urlKey=$urlKey")
        } else {
            logger.debug("强制刷新，跳过缓存 urlKey=$urlKey")
        }

        val pyBody = buildValidJson {
            kv("subtitle_url", p.subtitleUrl)
        }
        logger.debug("调用 Python /bilibili/video/subtitle-body urlKey=$urlKey")
        val raw = FredicaApi.PyUtil.post("/bilibili/video/subtitle-body", pyBody.str)
        val isSuccess = runCatching {
            val obj = raw.loadJson().getOrThrow() as? JsonObject
            val code = (obj?.get("code") as? JsonPrimitive)?.content?.toIntOrNull()
            code == 0
        }.getOrDefault(false)

        cache.insert(
            BilibiliSubtitleBodyCache(
                urlKey = urlKey,
                queriedAt = System.currentTimeMillis() / 1000L,
                rawResult = raw,
                isSuccess = isSuccess,
            )
        )
        logger.info("已写入缓存 urlKey=$urlKey is_success=$isSuccess raw_len=${raw.length}")
        return ValidJsonString(raw)
    }

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

@Serializable
data class BilibiliVideoSubtitleBodyParam(
    @SerialName("subtitle_url") val subtitleUrl: String,
    @SerialName("is_update") val isUpdate: Boolean = false,
)
