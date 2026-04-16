package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.auth.GuestInviteLinkService
import com.github.project_fredica.auth.GuestInviteVisitService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object GuestInviteLandingRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val requiresAuth = false
    override val minRole = AuthRole.GUEST
    override val desc = "游客邀请链接落地页（验证链接 + 记录访问）"

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val query = param.loadJsonModel<Map<String, List<String>>>().getOrElse {
            return buildJsonObject { put("error", "请求参数无效") }.toValidJson()
        }
        val pathId = query["path_id"]?.firstOrNull()
            ?: return buildJsonObject { put("error", "缺少 path_id") }.toValidJson()

        val link = GuestInviteLinkService.repo.findByPathId(pathId)
            ?: return buildJsonObject { put("error", "链接不存在") }.toValidJson()

        if (link.status != "active") {
            return buildJsonObject { put("error", "链接已禁用") }.toValidJson()
        }

        // 记录访问
        val ip = context.clientIp ?: ""
        val ua = context.userAgent ?: ""
        GuestInviteVisitService.repo.record(link.id, ip, ua)

        return buildJsonObject {
            put("success", true)
            put("label", link.label)
        }.toValidJson()
    }
}
