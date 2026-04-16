package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.auth.AuthIdentity
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.db.AppConfigService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object WebserverAuthTokenGetRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val minRole = AuthRole.ROOT
    override val desc = "读取游客令牌（仅 ROOT）"

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val identity = context.identity
        if (identity !is AuthIdentity.RootUser) {
            return buildJsonObject { put("error", "权限不足") }.toValidJson()
        }
        val config = AppConfigService.repo.getConfig()
        return buildJsonObject {
            put("webserver_auth_token", config.webserverAuthToken)
        }.toValidJson()
    }
}

object WebserverAuthTokenUpdateRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.ROOT
    override val desc = "更新游客令牌（仅 ROOT）"

    @Serializable
    data class Param(
        @SerialName("webserver_auth_token") val webserverAuthToken: String,
    )

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val identity = context.identity
        if (identity !is AuthIdentity.RootUser) {
            return buildJsonObject { put("error", "权限不足") }.toValidJson()
        }
        val p = param.loadJsonModel<Param>().getOrElse {
            return buildJsonObject { put("error", "请求参数无效") }.toValidJson()
        }
        AppConfigService.repo.updateConfigPartial(mapOf("webserver_auth_token" to p.webserverAuthToken))
        return buildJsonObject { put("success", true) }.toValidJson()
    }
}
