package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.auth.AuthServiceHolder
import com.github.project_fredica.auth.TenantInviteLinkService
import com.github.project_fredica.auth.TenantInviteRegistrationService
import com.github.project_fredica.auth.UserRecord
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object TenantInviteRegisterRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val requiresAuth = false
    override val minRole = AuthRole.GUEST
    override val desc = "通过租户邀请链接注册新用户"

    @Serializable
    data class Param(
        @SerialName("path_id") val pathId: String,
        val username: String,
        val password: String,
        @SerialName("display_name") val displayName: String = "",
    )

    @Serializable
    data class SuccessResponse(
        val success: Boolean = true,
        val token: String? = null,
        val user: UserRecord? = null,
    )

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val p = param.loadJsonModel<Param>().getOrElse {
            return buildJsonObject { put("error", "请求参数无效") }.toValidJson()
        }

        // 基本输入验证
        if (p.username.isBlank()) {
            return buildJsonObject { put("error", "用户名不能为空") }.toValidJson()
        }
        if (p.password.isBlank()) {
            return buildJsonObject { put("error", "密码不能为空") }.toValidJson()
        }

        // 1. 查找链接
        val link = TenantInviteLinkService.repo.findByPathId(p.pathId)
            ?: return buildJsonObject { put("error", "邀请链接不存在") }.toValidJson()

        // 2. 检查可用性 — 状态
        if (link.status != "active") {
            return buildJsonObject { put("error", "邀请链接已禁用") }.toValidJson()
        }

        // 2. 检查可用性 — 过期
        try {
            if (Instant.parse(link.expiresAt) <= Clock.System.now()) {
                return buildJsonObject { put("error", "邀请链接已过期") }.toValidJson()
            }
        } catch (_: Exception) {
            return buildJsonObject { put("error", "邀请链接已过期") }.toValidJson()
        }

        // 2. 检查可用性 — 名额
        val usedCount = TenantInviteRegistrationService.repo.countByLinkId(link.id)
        if (usedCount >= link.maxUses) {
            return buildJsonObject { put("error", "邀请链接已达使用上限") }.toValidJson()
        }

        // 3. 创建用户
        val createResult = AuthServiceHolder.instance.createUser(
            username = p.username,
            displayName = p.displayName.ifBlank { p.username },
            password = p.password,
        )
        if (!createResult.success || createResult.user == null) {
            return buildJsonObject { put("error", createResult.error ?: "创建用户失败") }.toValidJson()
        }

        // 4. 记录注册
        val ip = context.clientIp ?: ""
        val ua = context.userAgent ?: ""
        TenantInviteRegistrationService.repo.record(link.id, createResult.user.id, ip, ua)

        // 5. 自动登录
        val loginResult = AuthServiceHolder.instance.login(
            username = p.username,
            password = p.password,
            userAgent = ua,
            ipAddress = ip,
        )

        return AppUtil.dumpJsonStr(SuccessResponse(
            success = true,
            token = loginResult.token,
            user = loginResult.user,
        )).getOrThrow()
    }
}
