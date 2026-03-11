package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.db.BilibiliSubtitleMetaCacheService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object BilibiliVideoSubtitleRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "获取 B 站视频字幕元信息"

    private val logger = createLogger { "BilibiliVideoSubtitleRoute" }

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<BilibiliVideoSubtitleParam>().getOrThrow()
        logger.debug("请求 bvid=${p.bvid} page=${p.pageIndex} is_update=${p.isUpdate}")

        val cfg = AppConfigService.repo.getConfig()
        val hasCreds = !cfg.bilibiliSessdata.isNullOrBlank()

        val raw = BilibiliSubtitleMetaCacheService.fetchOrLoad(p.bvid, p.pageIndex, p.isUpdate) {
            val pyBody = buildValidJson {
                kv("sessdata", cfg.bilibiliSessdata)
                kv("bili_jct", cfg.bilibiliBiliJct)
                kv("buvid3", cfg.bilibiliBuvid3)
                kv("buvid4", cfg.bilibiliBuvid4)
                kv("dedeuserid", cfg.bilibiliDedeuserid)
                kv("ac_time_value", cfg.bilibiliAcTimeValue)
                kv("proxy", cfg.bilibiliProxy)
            }
            logger.debug("调用 Python bvid=${p.bvid} page=${p.pageIndex}")
            val pyResult = FredicaApi.PyUtil.post(
                "/bilibili/video/subtitle-meta/${p.bvid}/${p.pageIndex}", pyBody.str, timeoutMs = 5 * 60_000L
            )
            pyResult to computeIsSuccess(pyResult, p.bvid, hasCreds)
        }
        logger.debug("返回结果 bvid=${p.bvid} page=${p.pageIndex}")
        return ValidJsonString(raw)
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
    private fun computeIsSuccess(raw: String, bvid: String, hasCreds: Boolean): Boolean =
        runCatching {
            val obj = raw.loadJson().getOrThrow() as? JsonObject
            val code = (obj?.get("code") as? JsonPrimitive)?.content?.toIntOrNull()
            if (code != 0) return@runCatching false

            val subtitlesElem = obj.get("subtitles")
            if (subtitlesElem == null || subtitlesElem is JsonNull) {
                logger.warn("isSuccess=false bvid=$bvid: code=0 但 subtitles 为 null，视为查询失败")
                return@runCatching false
            }

            val subtitlesArr = subtitlesElem as? JsonArray
            val isEmpty = subtitlesArr == null || subtitlesArr.isEmpty()
            if (isEmpty && !hasCreds) {
                logger.warn(
                    "isSuccess=false bvid=$bvid: 无登录凭据且字幕列表为空，" +
                    "可能是 Session 失效导致 AI 字幕不可见，不缓存为成功"
                )
                return@runCatching false
            }

            true
        }.getOrDefault(false)
}

@Serializable
data class BilibiliVideoSubtitleParam(
    val bvid: String,
    @SerialName("page_index") val pageIndex: Int = 0,
    @SerialName("is_update") val isUpdate: Boolean = false,
)
