package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.auth.AuthIdentity
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.auth.TenantInviteLinkService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object TenantInviteLinkCreateRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.ROOT
    override val desc = "创建租户邀请链接"

    @Serializable
    data class Param(
        val label: String = "",
        @SerialName("path_id") val pathId: String = "",
        @SerialName("max_uses") val maxUses: Int = 1,
        @SerialName("expires_at") val expiresAt: String,
    )

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val identity = context.authenticatedUser
            ?: return buildJsonObject { put("error", "未登录") }.toValidJson()

        val p = param.loadJsonModel<Param>().getOrElse {
            return buildJsonObject { put("error", "请求参数无效") }.toValidJson()
        }

        val pathId = p.pathId.ifBlank { generatePathId() }

        val id = try {
            TenantInviteLinkService.repo.create(
                pathId = pathId,
                label = p.label,
                maxUses = p.maxUses,
                expiresAt = p.expiresAt,
                createdBy = identity.userId,
            )
        } catch (e: Exception) {
            return buildJsonObject { put("error", e.message ?: "创建失败") }.toValidJson()
        }

        return buildJsonObject {
            put("success", true)
            put("id", id)
            put("path_id", pathId)
        }.toValidJson()
    }
}
