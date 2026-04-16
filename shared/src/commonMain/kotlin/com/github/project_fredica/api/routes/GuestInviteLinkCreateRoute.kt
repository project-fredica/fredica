package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.auth.AuthIdentity
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.auth.GuestInviteLinkService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object GuestInviteLinkCreateRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.ROOT
    override val desc = "创建游客邀请链接"

    @Serializable
    data class Param(
        val label: String = "",
        @SerialName("path_id") val pathId: String = "",
    )

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val identity = context.authenticatedUser
            ?: return buildJsonObject { put("error", "未登录") }.toValidJson()

        val p = param.loadJsonModel<Param>().getOrElse {
            return buildJsonObject { put("error", "请求参数无效") }.toValidJson()
        }

        val pathId = p.pathId.ifBlank { generatePathId() }

        val id = try {
            GuestInviteLinkService.repo.create(
                pathId = pathId,
                label = p.label,
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

/** 生成 8 字符随机 pathId（小写字母 + 数字） */
internal fun generatePathId(length: Int = 8): String {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    return (1..length).map { chars.random() }.joinToString("")
}
