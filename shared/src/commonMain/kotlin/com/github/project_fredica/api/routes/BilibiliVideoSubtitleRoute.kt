package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.db.BilibiliSubtitleMetaCache
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
        val cache = BilibiliSubtitleMetaCacheService.repo

        if (!p.isUpdate) {
            val cached = cache.queryBest(p.bvid, p.pageIndex)
            if (cached != null) {
                logger.debug("命中缓存 bvid=${p.bvid} page=${p.pageIndex} queried_at=${cached.queriedAt}")
                return ValidJsonString(cached.rawResult)
            }
            logger.debug("无缓存，发起 Python 请求 bvid=${p.bvid} page=${p.pageIndex}")
        } else {
            logger.debug("强制刷新，跳过缓存 bvid=${p.bvid} page=${p.pageIndex}")
        }

        val cfg = AppConfigService.repo.getConfig()
        // 是否携带有效凭据（sessdata 非空即视为有凭据）
        val hasCreds = !cfg.bilibiliSessdata.isNullOrBlank()
        val pyBody = buildValidJson {
            kv("sessdata", cfg.bilibiliSessdata)
            kv("bili_jct", cfg.bilibiliBiliJct)
            kv("buvid3", cfg.bilibiliBuvid3)
            kv("buvid4", cfg.bilibiliBuvid4)
            kv("dedeuserid", cfg.bilibiliDedeuserid)
            kv("ac_time_value", cfg.bilibiliAcTimeValue)
            kv("proxy", cfg.bilibiliProxy)
        }
        val raw = FredicaApi.PyUtil.post("/bilibili/video/subtitle-meta/${p.bvid}/${p.pageIndex}", pyBody.str)

        // ── isSuccess 判定（三重校验，防止把"未登录时空字幕"误缓存为成功）─────────────
        //
        // B站 API 在 Session 失效或未登录时不会抛错，而是静默返回：
        //   {"code": 0, "subtitles": []}
        // 即 code=0 但字幕列表为空——AI 字幕（ai-zh）对未登录用户不可见。
        //
        // 若以 code==0 作为唯一成功判据，这条"假成功"会被缓存为 is_success=1，
        // 导致后续带有效凭据的请求（is_update=false）也命中缓存，显示"该视频暂无字幕"。
        //
        // 判定规则：
        //   1. code 必须为 0
        //   2. subtitles 字段不得为 null（API 异常时 Python 返回 "subtitles": null）
        //   3. 若 subtitles 为空列表（[]）且当前请求无凭据 → isSuccess=false
        //      原因：无凭据时无法区分"视频真的没有字幕"与"需要登录才能看到字幕"；
        //            不缓存为成功，确保用户登录后能重新获取到真实字幕。
        //      若携带凭据返回空列表 → isSuccess=true（该视频确实没有字幕）。
        val isSuccess = runCatching {
            val obj = raw.loadJson().getOrThrow() as? JsonObject
            val code = (obj?.get("code") as? JsonPrimitive)?.content?.toIntOrNull()
            if (code != 0) return@runCatching false  // B站业务错误或认证失败

            val subtitlesElem = obj?.get("subtitles")
            if (subtitlesElem == null || subtitlesElem is JsonNull) {
                // subtitles 字段缺失或为 null，说明 Python 层捕获了异常
                logger.warn("isSuccess=false bvid=${p.bvid}: code=0 但 subtitles 为 null，视为查询失败")
                return@runCatching false
            }

            val subtitlesArr = subtitlesElem as? JsonArray
            val isEmpty = subtitlesArr == null || subtitlesArr.isEmpty()
            if (isEmpty && !hasCreds) {
                // 无凭据 + 空字幕：B站 AI 字幕对未登录不可见，无法判定"真的没有字幕"
                logger.warn(
                    "isSuccess=false bvid=${p.bvid}: 无登录凭据且字幕列表为空，" +
                    "可能是 Session 失效导致 AI 字幕不可见，不缓存为成功"
                )
                return@runCatching false
            }

            true
        }.getOrDefault(false)

        cache.insert(
            BilibiliSubtitleMetaCache(
                bvid = p.bvid,
                pageIndex = p.pageIndex,
                queriedAt = System.currentTimeMillis() / 1000L,
                rawResult = raw,
                isSuccess = isSuccess,
            )
        )
        logger.info("已写入缓存 bvid=${p.bvid} page=${p.pageIndex} is_success=$isSuccess")
        return ValidJsonString(raw)
    }
}

@Serializable
data class BilibiliVideoSubtitleParam(
    val bvid: String,
    @SerialName("page_index") val pageIndex: Int = 0,
    @SerialName("is_update") val isUpdate: Boolean = false,
)
