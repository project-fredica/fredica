package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.auth.AuditLogEntry
import com.github.project_fredica.auth.AuditLogService
import com.github.project_fredica.auth.AuthIdentity
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.auth.UserService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object UserUpdateDisplayNameRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.TENANT
    override val desc = "更新显示名（TENANT 改自己，ROOT 可改他人）"

    @Serializable
    data class Param(
        @SerialName("user_id") val userId: String? = null,
        @SerialName("display_name") val displayName: String,
    )

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val identity = context.identity
        if (identity !is AuthIdentity.Authenticated) {
            return buildJsonObject { put("error", "Guest 身份无法更新显示名") }.toValidJson()
        }

        val p = param.loadJsonModel<Param>().getOrElse {
            return buildJsonObject { put("error", "请求参数无效") }.toValidJson()
        }

        val targetUserId = p.userId ?: identity.userId

        // 非 ROOT 只能改自己
        if (targetUserId != identity.userId && identity.role != AuthRole.ROOT) {
            return buildJsonObject { put("error", "权限不足，仅 ROOT 可修改他人显示名") }.toValidJson()
        }

        val displayName = p.displayName.trim()
        if (displayName.isEmpty() || displayName.length > 64) {
            return buildJsonObject { put("error", "显示名长度需 1-64 字符") }.toValidJson()
        }

        val user = UserService.repo.findById(targetUserId)
            ?: return buildJsonObject { put("error", "用户不存在") }.toValidJson()

        UserService.repo.updateDisplayName(targetUserId, displayName)

        AuditLogService.repo.insert(
            AuditLogEntry(
                id = "",
                timestamp = 0L,
                eventType = "USER_DISPLAY_NAME_UPDATED",
                actorUserId = identity.userId,
                actorUsername = identity.username,
                targetUserId = targetUserId,
                details = "display name: ${user.displayName} -> $displayName",
            )
        )

        return buildJsonObject {
            put("success", true)
            put("display_name", displayName)
        }.toValidJson()
    }
}
