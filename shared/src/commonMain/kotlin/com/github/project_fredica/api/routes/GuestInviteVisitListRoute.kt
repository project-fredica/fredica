package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.auth.GuestInviteVisitService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object GuestInviteVisitListRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val minRole = AuthRole.ROOT
    override val desc = "查看游客邀请链接的访问记录"

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val query = param.loadJsonModel<Map<String, List<String>>>().getOrElse {
            return buildJsonObject { put("error", "请求参数无效") }.toValidJson()
        }
        val linkId = query["link_id"]?.firstOrNull()
            ?: return buildJsonObject { put("error", "缺少 link_id") }.toValidJson()
        val limit = query["limit"]?.firstOrNull()?.toIntOrNull() ?: 100
        val offset = query["offset"]?.firstOrNull()?.toIntOrNull() ?: 0

        val visits = GuestInviteVisitService.repo.listByLinkId(linkId, limit, offset)
        val count = GuestInviteVisitService.repo.countByLinkId(linkId)

        return buildJsonObject {
            put("total", count)
            put("items", kotlinx.serialization.json.JsonArray(
                visits.map { v ->
                    kotlinx.serialization.json.buildJsonObject {
                        put("id", v.id)
                        put("link_id", v.linkId)
                        put("ip_address", v.ipAddress)
                        put("user_agent", v.userAgent)
                        put("visited_at", v.visitedAt)
                    }
                }
            ))
        }.toValidJson()
    }
}
