package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.auth.AuditLogEntry
import com.github.project_fredica.auth.AuditLogService
import com.github.project_fredica.auth.AuthServiceHolder
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.auth.LoginRateLimiterHolder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object AuthLoginRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "用户名密码登录"
    override val requiresAuth = false
    override val minRole = AuthRole.GUEST

    @Serializable
    data class Param(
        val username: String,
        val password: String,
        @SerialName("user_agent") val userAgent: String = "",
        @SerialName("ip_address") val ipAddress: String = "",
    )

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val p = param.loadJsonModel<Param>().getOrElse {
            return buildJsonObject { put("error", "请求参数无效") }.toValidJson()
        }

        // 频率限制
        val waitSeconds = LoginRateLimiterHolder.instance.check(p.ipAddress, p.username)
        if (waitSeconds != null) {
            AuditLogService.repo.insert(
                AuditLogEntry(
                    id = "",
                    timestamp = 0L,
                    eventType = "LOGIN_RATE_LIMITED",
                    actorUsername = p.username,
                    ipAddress = p.ipAddress,
                    details = "rate limited, wait ${waitSeconds}s",
                )
            )
            return buildJsonObject {
                put("error", "登录尝试过于频繁，请 ${waitSeconds} 秒后重试")
                put("retry_after", waitSeconds)
            }.toValidJson()
        }

        val result = AuthServiceHolder.instance.login(
            username = p.username,
            password = p.password,
            userAgent = p.userAgent,
            ipAddress = p.ipAddress,
        )

        if (result.success) {
            LoginRateLimiterHolder.instance.clearOnSuccess(p.ipAddress, p.username)
            AuditLogService.repo.insert(
                AuditLogEntry(
                    id = "",
                    timestamp = 0L,
                    eventType = "LOGIN_SUCCESS",
                    actorUserId = result.user?.id,
                    actorUsername = p.username,
                    ipAddress = p.ipAddress,
                    userAgent = p.userAgent,
                )
            )
        } else {
            LoginRateLimiterHolder.instance.recordFailure(p.ipAddress, p.username)
            AuditLogService.repo.insert(
                AuditLogEntry(
                    id = "",
                    timestamp = 0L,
                    eventType = "LOGIN_FAILED",
                    actorUsername = p.username,
                    ipAddress = p.ipAddress,
                    userAgent = p.userAgent,
                    details = result.error,
                )
            )
        }

        return AppUtil.dumpJsonStr(result).getOrThrow()
    }
}
