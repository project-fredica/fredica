package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.auth.AuthRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object BilibiliVideoGetInfoRoute : FredicaApi.Route {
    private val logger = createLogger()
    override val mode: FredicaApi.Route.Mode
        get() = FredicaApi.Route.Mode.Post
    override val desc: String
        get() = "获取bilibili视频的详细信息（含分P列表、UP主、统计数据）"
    override val minRole = AuthRole.GUEST

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        return try {
            val p = param.loadJsonModel<BilibiliVideoGetInfoParam>().getOrThrow()
            logger.debug("请求 bvid=${p.bvid}")
            val body = buildJsonObject { }.toValidJson()
            val raw = FredicaApi.PyUtil.post("/bilibili/video/get-info/${p.bvid}", body.str)
            logger.debug("Python 返回 bvid=${p.bvid} raw_len=${raw.length} raw_preview=${raw.take(300)}")
            ValidJsonString(raw)
        } catch (e: Throwable) {
            logger.warn("[BilibiliVideoGetInfoRoute] 获取视频信息失败", isHappensFrequently = false, err = e)
            buildJsonObject { put("error", e.message ?: "unknown") }.toValidJson()
        }
    }
}

@Serializable
data class BilibiliVideoGetInfoParam(val bvid: String)
