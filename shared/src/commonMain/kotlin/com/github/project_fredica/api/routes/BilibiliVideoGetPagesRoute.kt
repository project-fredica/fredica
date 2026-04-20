package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.auth.AuthRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

object BilibiliVideoGetPagesRoute : FredicaApi.Route {
    private val logger = createLogger()
    override val mode: FredicaApi.Route.Mode
        get() = FredicaApi.Route.Mode.Post
    override val desc: String
        get() = "获取bilibili视频的全部分P列表"
    override val minRole = AuthRole.GUEST

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        return try {
            val p = param.loadJsonModel<BilibiliVideoGetPagesParam>().getOrThrow()
            logger.debug("请求 bvid=${p.bvid}")
            val body = buildJsonObject { put("bvid", p.bvid) }.toValidJson()
            val raw = FredicaApi.PyUtil.post("/bilibili/video/get-pages/${p.bvid}", body.str)
            logger.debug("Python 返回 bvid=${p.bvid} raw_len=${raw.length} raw_preview=${raw.take(300)}")

            val obj = raw.loadJson().getOrThrow() as? JsonObject
            val errorField = (obj?.get("error") as? JsonPrimitive)?.content
            if (errorField != null) {
                logger.warn("Python 返回 error bvid=${p.bvid}: $errorField")
                return buildJsonObject { put("error", errorField) }.toValidJson()
            }

            val pagesArr = obj?.get("pages") as? JsonArray
            if (pagesArr == null) {
                logger.warn("Python 返回中无 pages 字段 bvid=${p.bvid}")
                return buildJsonObject { put("error", "Python 返回格式异常：无 pages 字段") }.toValidJson()
            }

            val result = buildJsonArray {
                for (item in pagesArr) {
                    val page = item as? JsonObject ?: continue
                    add(buildJsonObject {
                        put("page", (page["page"] as? JsonPrimitive)?.intOrNull ?: 0)
                        put("title", (page["part"] as? JsonPrimitive)?.content ?: "")
                        put("duration", (page["duration"] as? JsonPrimitive)?.intOrNull ?: 0)
                        put("cover", (page["first_frame"] as? JsonPrimitive)?.content ?: "")
                    })
                }
            }
            logger.debug("转换完成 bvid=${p.bvid} pageCount=${result.size}")
            ValidJsonString(result.toString())
        } catch (e: Throwable) {
            logger.warn("[BilibiliVideoGetPagesRoute] 获取分P失败", isHappensFrequently = false, err = e)
            buildJsonObject { put("error", e.message ?: "unknown") }.toValidJson()
        }
    }
}

@Serializable
data class BilibiliVideoGetPagesParam(val bvid: String)

@Serializable
data class BilibiliPageInfo(
    val page: Int,
    val title: String,
    val duration: Int,
    val cover: String = "",
)
