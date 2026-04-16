package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.auth.TenantInviteRegistrationService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object TenantInviteRegistrationListRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val minRole = AuthRole.ROOT
    override val desc = "查看租户邀请链接的注册记录"

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val query = param.loadJsonModel<Map<String, List<String>>>().getOrElse {
            return buildJsonObject { put("error", "请求参数无效") }.toValidJson()
        }
        val linkId = query["link_id"]?.firstOrNull()
            ?: return buildJsonObject { put("error", "缺少 link_id") }.toValidJson()

        val registrations = TenantInviteRegistrationService.repo.listByLinkId(linkId)
        val count = TenantInviteRegistrationService.repo.countByLinkId(linkId)

        return buildJsonObject {
            put("total", count)
            put("items", kotlinx.serialization.json.JsonArray(
                registrations.map { r ->
                    kotlinx.serialization.json.buildJsonObject {
                        put("id", r.id)
                        put("link_id", r.linkId)
                        put("user_id", r.userId)
                        put("ip_address", r.ipAddress)
                        put("user_agent", r.userAgent)
                        put("registered_at", r.registeredAt)
                        r.username?.let { put("username", it) }
                        r.displayName?.let { put("display_name", it) }
                    }
                }
            ))
        }.toValidJson()
    }
}
