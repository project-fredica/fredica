package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.asr.route_ext.handler2
import com.github.project_fredica.auth.AuthRole

/**
 * GET /api/v1/MaterialSubtitleListRoute?material_id=xxx
 *
 * 查询素材已缓存的字幕列表。
 * 不触发任何网络请求，仅读取本地缓存与落盘文件。
 *
 * 返回：List<MaterialSubtitleItem>（JSON 数组）
 */
object MaterialSubtitleListRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "查询素材已缓存的字幕列表"
    override val minRole = AuthRole.GUEST

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString = handler2(param)
}
