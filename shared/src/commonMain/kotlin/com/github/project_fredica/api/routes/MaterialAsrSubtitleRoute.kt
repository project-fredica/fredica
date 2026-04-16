package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.asr.route_ext.handler2
import com.github.project_fredica.auth.AuthRole

/**
 * GET /api/v1/MaterialAsrSubtitleRoute?material_id=xxx&model_size=large-v3
 *
 * 读取 ASR 转录结果，返回分段字幕 + 元数据。
 *
 * 优先读取合并后的 transcript.srt（transcript.done 存在时），
 * 否则读取各 chunk_XXXX.srt 拼接（partial 模式）。
 */
object MaterialAsrSubtitleRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "读取 ASR 转录结果"
    override val minRole = AuthRole.GUEST

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString = handler2(param)
}
