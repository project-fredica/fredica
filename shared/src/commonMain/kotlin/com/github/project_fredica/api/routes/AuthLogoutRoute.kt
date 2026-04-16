package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.auth.AuditLogEntry
import com.github.project_fredica.auth.AuditLogService
import com.github.project_fredica.auth.AuthIdentity
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.auth.AuthServiceHolder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object AuthLogoutRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.TENANT
    override val desc = "登出（销毁当前 session）"

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val identity = context.identity
        if (identity !is AuthIdentity.Authenticated) {
            return buildJsonObject { put("error", "Guest 身份无法登出") }.toValidJson()
        }

        AuthServiceHolder.instance.logout(identity.sessionId)

        AuditLogService.repo.insert(
            AuditLogEntry(
                id = "",
                timestamp = 0L,
                eventType = "LOGOUT",
                actorUserId = identity.userId,
                actorUsername = identity.username,
            )
        )

        return buildJsonObject { put("success", true) }.toValidJson()
    }
}
