package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.asr.route_ext.handler2

/**
 * POST /api/v1/AsrConfigSaveRoute
 *
 * 保存 ASR 权限与测试配置（部分更新）。
 */
object AsrConfigSaveRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "保存 ASR 配置"

    override suspend fun handler(param: String): ValidJsonString = handler2(param)
}
