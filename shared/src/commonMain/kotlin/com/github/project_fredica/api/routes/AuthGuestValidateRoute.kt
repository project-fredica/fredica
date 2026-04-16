package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.auth.AuthIdentity
import com.github.project_fredica.auth.AuthRole
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object AuthGuestValidateRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val minRole = AuthRole.GUEST
    override val desc = "验证当前 Bearer Token 是否有效（用于游客登录验证）"

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val identity = context.identity
        return buildJsonObject {
            put("valid", true)
            put("role", when (identity) {
                is AuthIdentity.Authenticated -> identity.role.name.lowercase()
                is AuthIdentity.Guest -> "guest"
                else -> "unknown"
            })
        }.toValidJson()
    }
}
