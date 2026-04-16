package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.auth.TenantInviteLinkService
import com.github.project_fredica.auth.TenantInviteRegistrationService
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object TenantInviteLandingRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val requiresAuth = false
    override val minRole = AuthRole.GUEST
    override val desc = "租户邀请链接落地页（检查链接可用性）"

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val query = param.loadJsonModel<Map<String, List<String>>>().getOrElse {
            return buildJsonObject { put("error", "请求参数无效") }.toValidJson()
        }
        val pathId = query["path_id"]?.firstOrNull()
            ?: return buildJsonObject { put("error", "缺少 path_id") }.toValidJson()

        val link = TenantInviteLinkService.repo.findByPathId(pathId)
            ?: return buildJsonObject { put("error", "链接不存在") }.toValidJson()

        // 检查状态
        if (link.status != "active") {
            return buildJsonObject {
                put("usable", false)
                put("reason", "disabled")
            }.toValidJson()
        }

        // 检查过期
        try {
            if (Instant.parse(link.expiresAt) <= Clock.System.now()) {
                return buildJsonObject {
                    put("usable", false)
                    put("reason", "expired")
                }.toValidJson()
            }
        } catch (_: Exception) {
            // expiresAt 格式异常视为过期
            return buildJsonObject {
                put("usable", false)
                put("reason", "expired")
            }.toValidJson()
        }

        // 检查名额
        val usedCount = TenantInviteRegistrationService.repo.countByLinkId(link.id)
        if (usedCount >= link.maxUses) {
            return buildJsonObject {
                put("usable", false)
                put("reason", "full")
            }.toValidJson()
        }

        return buildJsonObject {
            put("usable", true)
            put("label", link.label)
        }.toValidJson()
    }
}
