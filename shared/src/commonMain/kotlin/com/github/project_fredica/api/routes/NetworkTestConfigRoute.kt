package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add

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

    override suspend fun handler(param: String): ValidJsonString {
        return buildValidJson {
            kv("urls", buildJsonArray { NETWORK_TEST_DEFAULT_URLS.forEach { add(it) } })
        }
    }
}
