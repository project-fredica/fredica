package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.auth.AuditLogEntry
import com.github.project_fredica.auth.AuditLogService
import com.github.project_fredica.auth.AuthIdentity
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.auth.AuthServiceHolder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object UserChangePasswordRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.TENANT
    override val desc = "修改密码（需验证旧密码）"

    @Serializable
    data class Param(
        @SerialName("old_password") val oldPassword: String,
        @SerialName("new_password") val newPassword: String,
    )

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val identity = context.identity
        if (identity !is AuthIdentity.Authenticated) {
            return buildJsonObject { put("error", "Guest 身份无法修改密码") }.toValidJson()
        }

        val p = param.loadJsonModel<Param>().getOrElse {
            return buildJsonObject { put("error", "请求参数无效") }.toValidJson()
        }

        // 新密码校验：8-128 字符，非全空白
        if (p.newPassword.length < 8 || p.newPassword.length > 128 || p.newPassword.isBlank()) {
            return buildJsonObject { put("error", "新密码长度需 8-128 字符且不能全为空白") }.toValidJson()
        }

        val result = AuthServiceHolder.instance.changePassword(
            userId = identity.userId,
            oldPassword = p.oldPassword,
            newPassword = p.newPassword,
            currentSessionId = identity.sessionId,
        )

        if (result.success) {
            AuditLogService.repo.insert(
                AuditLogEntry(
                    id = "",
                    timestamp = 0L,
                    eventType = "PASSWORD_CHANGED",
                    actorUserId = identity.userId,
                    actorUsername = identity.username,
                )
            )
        }

        return AppUtil.dumpJsonStr(result).getOrThrow()
    }
}
