package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.auth.TenantInviteLinkService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object TenantInviteLinkDeleteRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.ROOT
    override val desc = "删除租户邀请链接（有注册记录时拒绝）"

    @Serializable
    data class Param(val id: String)

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val p = param.loadJsonModel<Param>().getOrElse {
            return buildJsonObject { put("error", "请求参数无效") }.toValidJson()
        }

        TenantInviteLinkService.repo.findById(p.id)
            ?: return buildJsonObject { put("error", "链接不存在") }.toValidJson()

        try {
            TenantInviteLinkService.repo.delete(p.id)
        } catch (e: Exception) {
            return buildJsonObject { put("error", e.message ?: "删除失败") }.toValidJson()
        }

        return buildJsonObject { put("success", true) }.toValidJson()
    }
}
