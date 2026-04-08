package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJson
import com.github.project_fredica.apputil.loadJsonModel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.db.BilibiliAiConclusionCacheService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object BilibiliVideoAiConclusionRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "获取 B 站视频 AI 总结"

    private val logger = createLogger { "BilibiliVideoAiConclusionRoute" }

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<BilibiliVideoAiConclusionParam>().getOrThrow()
        logger.debug("请求 bvid=${p.bvid} page=${p.pageIndex} is_update=${p.isUpdate}")

        val cfg = AppConfigService.repo.getConfig()

        val raw = BilibiliAiConclusionCacheService.fetchOrLoad(p.bvid, p.pageIndex, p.isUpdate) {
            val pyBody = buildJsonObject {
                put("sessdata", cfg.bilibiliSessdata)
                put("bili_jct", cfg.bilibiliBiliJct)
                put("buvid3", cfg.bilibiliBuvid3)
                put("buvid4", cfg.bilibiliBuvid4)
                put("dedeuserid", cfg.bilibiliDedeuserid)
                put("ac_time_value", cfg.bilibiliAcTimeValue)
                put("proxy", cfg.bilibiliProxy)
            }.toValidJson()
            logger.debug("调用 Python bvid=${p.bvid} page=${p.pageIndex}")
            val pyResult = FredicaApi.PyUtil.post(
                "/bilibili/video/ai-conclusion/${p.bvid}/${p.pageIndex}", pyBody.str
            )
            pyResult to computeIsSuccess(pyResult)
        }
        logger.debug("返回结果 bvid=${p.bvid} page=${p.pageIndex}")
        return ValidJsonString(raw)
    }

    private fun computeIsSuccess(raw: String): Boolean =
        runCatching {
            val obj = raw.loadJson().getOrThrow() as? JsonObject
            val code = (obj?.get("code") as? JsonPrimitive)?.content?.toIntOrNull()
            val modelResult = obj?.get("model_result")
            code == 0 && modelResult != null && modelResult !is JsonNull
        }.getOrDefault(false)
}

@Serializable
data class BilibiliVideoAiConclusionParam(
    val bvid: String,
    @SerialName("page_index") val pageIndex: Int = 0,
    @SerialName("is_update") val isUpdate: Boolean = false,
)
