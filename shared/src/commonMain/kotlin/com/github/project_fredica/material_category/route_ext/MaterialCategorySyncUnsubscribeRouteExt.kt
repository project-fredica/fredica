package com.github.project_fredica.material_category.route_ext

import com.github.project_fredica.api.routes.MaterialCategorySyncUnsubscribeRoute
import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.auth.AuthIdentity
import com.github.project_fredica.material_category.model.MaterialCategoryAuditLog
import com.github.project_fredica.material_category.service.MaterialCategoryAuditLogService
import com.github.project_fredica.material_category.service.MaterialCategorySyncPlatformInfoService
import com.github.project_fredica.material_category.service.MaterialCategorySyncUserConfigService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

@Suppress("UnusedReceiverParameter")
suspend fun MaterialCategorySyncUnsubscribeRoute.handler2(param: String, context: RouteContext): ValidJsonString {
    val userId = context.userId ?: return buildJsonObject { put("error", "未登录") }.toValidJson()
    val isRoot = context.identity is AuthIdentity.RootUser
    val p = param.loadJsonModel<MaterialCategorySyncUnsubscribeRequest>().getOrThrow()

    val userConfig = MaterialCategorySyncUserConfigService.repo.getById(p.userConfigId)
        ?: return buildJsonObject { put("error", "订阅不存在") }.toValidJson()

    if (userConfig.userId != userId && !isRoot) {
        return buildJsonObject { put("error", "权限不足") }.toValidJson()
    }

    val platformInfo = MaterialCategorySyncPlatformInfoService.repo.getById(userConfig.platformInfoId)

    MaterialCategorySyncUserConfigService.repo.deleteById(userConfig.id)

    if (platformInfo != null) {
        MaterialCategoryAuditLogService.repo.insert(
            MaterialCategoryAuditLog(
                id = UUID.randomUUID().toString(),
                categoryId = platformInfo.categoryId,
                userId = userId,
                action = "sync_unsubscribe",
                detail = buildJsonObject {
                    put("platform_info_id", platformInfo.id)
                    put("user_config_id", userConfig.id)
                }.toString(),
                createdAt = System.currentTimeMillis() / 1000L,
            )
        )
    }

    return buildJsonObject { put("success", true) }.toValidJson()
}

@Serializable
data class MaterialCategorySyncUnsubscribeRequest(
    @SerialName("user_config_id") val userConfigId: String,
)
