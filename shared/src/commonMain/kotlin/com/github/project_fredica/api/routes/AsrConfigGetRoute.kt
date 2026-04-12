package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.asr.route_ext.handler2

/**
 * GET /api/v1/AsrConfigGetRoute
 *
 * 读取 ASR 权限与测试配置。
 */
object AsrConfigGetRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "读取 ASR 配置"

    override suspend fun handler(param: String): ValidJsonString = handler2(param)
}
