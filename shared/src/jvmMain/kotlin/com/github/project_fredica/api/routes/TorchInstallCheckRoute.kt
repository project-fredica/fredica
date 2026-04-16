package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.python.TorchService
import com.github.project_fredica.auth.AuthRole

/**
 * GET /api/v1/TorchInstallCheckRoute
 *
 * 检查 pip 安装目录中是否已有可用 torch。
 *
 * 查询参数（可选）：
 * - expected_version: 期望版本号，为空时仅检查是否已安装
 *
 * 响应示例：
 * ```json
 * {"already_ok": true, "installed_version": "2.7.0+cu124"}
 * {"already_ok": false, "installed_version": null}
 * {"error": "..."}
 * ```
 */
object TorchInstallCheckRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "检查 torch 安装状态"
    override val minRole = AuthRole.TENANT

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val p = param.loadJsonModel<Map<String, List<String>>>().getOrElse { emptyMap() }
        val expectedVersion = p["expected_version"]?.firstOrNull() ?: ""
        return ValidJsonString(TorchService.check(expectedVersion))
    }
}
