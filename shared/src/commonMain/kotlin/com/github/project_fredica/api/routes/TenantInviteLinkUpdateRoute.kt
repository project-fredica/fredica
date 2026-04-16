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

object TenantInviteLinkUpdateRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.ROOT
    override val desc = "更新租户邀请链接（标签/状态）"

    @Serializable
    data class Param(
        val id: String,
        val label: String? = null,
        val status: String? = null,
    )

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val p = param.loadJsonModel<Param>().getOrElse {
            return buildJsonObject { put("error", "请求参数无效") }.toValidJson()
        }

        val link = TenantInviteLinkService.repo.findById(p.id)
            ?: return buildJsonObject { put("error", "链接不存在") }.toValidJson()

        try {
            if (p.label != null) {
                TenantInviteLinkService.repo.updateLabel(link.id, p.label)
            }
            if (p.status != null) {
                TenantInviteLinkService.repo.updateStatus(link.id, p.status)
            }
        } catch (e: Exception) {
            return buildJsonObject { put("error", e.message ?: "更新失败") }.toValidJson()
        }

        return buildJsonObject { put("success", true) }.toValidJson()
    }
}
