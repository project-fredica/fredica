package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.asr.route_ext.handler2
import com.github.project_fredica.auth.AuthRole

/**
 * POST /api/v1/MaterialSubtitleContentRoute
 *
 * 按通用素材字幕接口返回字幕全文。
 */
object MaterialSubtitleContentRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "获取素材字幕全文"
    override val minRole = AuthRole.GUEST

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString = handler2(param)
}
