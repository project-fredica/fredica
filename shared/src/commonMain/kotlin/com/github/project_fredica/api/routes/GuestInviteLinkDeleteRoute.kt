package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.auth.GuestInviteLinkService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object GuestInviteLinkDeleteRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.ROOT
    override val desc = "删除游客邀请链接（级联删除访问记录）"

    @Serializable
    data class Param(val id: String)

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val p = param.loadJsonModel<Param>().getOrElse {
            return buildJsonObject { put("error", "请求参数无效") }.toValidJson()
        }

        GuestInviteLinkService.repo.findById(p.id)
            ?: return buildJsonObject { put("error", "链接不存在") }.toValidJson()

        GuestInviteLinkService.repo.delete(p.id)
        return buildJsonObject { put("success", true) }.toValidJson()
    }
}
