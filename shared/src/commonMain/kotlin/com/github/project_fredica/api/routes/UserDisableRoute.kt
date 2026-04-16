package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.auth.AuditLogEntry
import com.github.project_fredica.auth.AuditLogService
import com.github.project_fredica.auth.AuthIdentity
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.auth.AuthSessionService
import com.github.project_fredica.auth.UserService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object UserDisableRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.ROOT
    override val desc = "禁用用户（仅 ROOT）"

    @Serializable
    data class Param(
        @SerialName("user_id") val userId: String,
    )

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val identity = context.identity
        if (identity !is AuthIdentity.RootUser) {
            return buildJsonObject { put("error", "权限不足，仅 ROOT 可禁用用户") }.toValidJson()
        }

        val p = param.loadJsonModel<Param>().getOrElse {
            return buildJsonObject { put("error", "请求参数无效") }.toValidJson()
        }

        // 不能禁用自己
        if (p.userId == identity.userId) {
            return buildJsonObject { put("error", "不能禁用自己") }.toValidJson()
        }

        val user = UserService.repo.findById(p.userId)
            ?: return buildJsonObject { put("error", "用户不存在") }.toValidJson()

        if (user.status == "disabled") {
            return buildJsonObject { put("error", "用户已处于禁用状态") }.toValidJson()
        }

        UserService.repo.updateStatus(p.userId, "disabled")

        // 级联销毁该用户所有 session
        AuthSessionService.repo.deleteByUserId(p.userId)

        AuditLogService.repo.insert(
            AuditLogEntry(
                id = "",
                timestamp = 0L,
                eventType = "USER_DISABLED",
                actorUserId = identity.userId,
                actorUsername = identity.username,
                targetUserId = p.userId,
                details = "disabled user: ${user.username}",
            )
        )

        return buildJsonObject { put("success", true) }.toValidJson()
    }
}
