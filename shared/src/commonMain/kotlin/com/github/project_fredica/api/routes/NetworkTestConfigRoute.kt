package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import com.github.project_fredica.auth.AuthRole

/**
 * GET /api/v1/NetworkTestConfigRoute
 *
 * 返回网速测试的默认配置（测试目标列表），供前端展示用。
 *
 * 响应：
 * ```json
 * {"urls": ["https://www.baidu.com", ...]}
 * ```
 */
object NetworkTestConfigRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "获取网速测试默认配置（测试目标列表）"
    override val minRole = AuthRole.GUEST

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        return buildJsonObject {
            put("urls", buildJsonArray { NETWORK_TEST_DEFAULT_URLS.forEach { add(it) } })
        }.toValidJson()
    }
}
