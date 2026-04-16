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

object UserCreateRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.ROOT
    override val desc = "创建新用户（仅 ROOT）"

    @Serializable
    data class Param(
        val username: String,
        val password: String,
        @SerialName("display_name") val displayName: String = "",
    )

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val identity = context.identity
        if (identity !is AuthIdentity.RootUser) {
            return buildJsonObject { put("error", "权限不足，仅 ROOT 可创建用户") }.toValidJson()
        }

        val p = param.loadJsonModel<Param>().getOrElse {
            return buildJsonObject { put("error", "请求参数无效") }.toValidJson()
        }

        // 用户名校验：3-32 字符，字母数字下划线
        if (!p.username.matches(Regex("^[a-zA-Z0-9_]{3,32}$"))) {
            return buildJsonObject { put("error", "用户名格式无效（3-32 字符，仅字母数字下划线）") }.toValidJson()
        }

        // 密码校验：8-128 字符，非全空白
        if (p.password.length < 8 || p.password.length > 128 || p.password.isBlank()) {
            return buildJsonObject { put("error", "密码长度需 8-128 字符且不能全为空白") }.toValidJson()
        }

        val displayName = p.displayName.trim().ifEmpty { p.username }
        // 显示名校验：1-64 字符
        if (displayName.length > 64) {
            return buildJsonObject { put("error", "显示名长度不能超过 64 字符") }.toValidJson()
        }

        val result = AuthServiceHolder.instance.createUser(
            username = p.username,
            displayName = displayName,
            password = p.password,
        )

        if (result.success && result.user != null) {
            AuditLogService.repo.insert(
                AuditLogEntry(
                    id = "",
                    timestamp = 0L,
                    eventType = "USER_CREATED",
                    actorUserId = identity.userId,
                    actorUsername = identity.username,
                    targetUserId = result.user.id,
                    details = "created user: ${result.user.username}",
                )
            )
        }

        return AppUtil.dumpJsonStr(result).getOrThrow()
    }
}
