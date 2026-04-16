package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.auth.AuthIdentity
import com.github.project_fredica.auth.AuthRole
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object AuthMeRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val minRole = AuthRole.TENANT
    override val desc = "获取当前登录用户信息"

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        return when (val identity = context.identity) {
            is AuthIdentity.Authenticated -> buildJsonObject {
                put("user_id", identity.userId)
                put("username", identity.username)
                put("display_name", identity.displayName)
                put("role", identity.role.name.lowercase())
                put("permissions", identity.permissions)
                put("session_id", identity.sessionId)
            }.toValidJson()

            is AuthIdentity.Guest -> buildJsonObject {
                put("role", "guest")
            }.toValidJson()

            else -> buildJsonObject {
                put("error", "未知身份类型")
            }.toValidJson()
        }
    }
}
