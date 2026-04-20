package com.github.project_fredica.material_category.route_ext

import com.github.project_fredica.api.routes.MaterialCategorySyncUserConfigUpdateRoute
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
suspend fun MaterialCategorySyncUserConfigUpdateRoute.handler2(param: String, context: RouteContext): ValidJsonString {
    val userId = context.userId ?: return buildJsonObject { put("error", "未登录") }.toValidJson()
    val isRoot = context.identity is AuthIdentity.RootUser
    val p = param.loadJsonModel<MaterialCategorySyncUserConfigUpdateRequest>().getOrThrow()

    val userConfig = MaterialCategorySyncUserConfigService.repo.getById(p.userConfigId)
        ?: return buildJsonObject { put("error", "订阅不存在") }.toValidJson()

    if (userConfig.userId != userId && !isRoot) {
        return buildJsonObject { put("error", "权限不足") }.toValidJson()
    }

    val updated = MaterialCategorySyncUserConfigService.repo.update(
        id = userConfig.id,
        enabled = p.enabled,
        cronExpr = p.cronExpr,
        freshnessWindowSec = p.freshnessWindowSec,
    )
    if (!updated) return buildJsonObject { put("error", "更新失败") }.toValidJson()

    val platformInfo = MaterialCategorySyncPlatformInfoService.repo.getById(userConfig.platformInfoId)
    if (platformInfo != null) {
        MaterialCategoryAuditLogService.repo.insert(
            MaterialCategoryAuditLog(
                id = UUID.randomUUID().toString(),
                categoryId = platformInfo.categoryId,
                userId = userId,
                action = "sync_user_config_update",
                detail = buildJsonObject {
                    put("user_config_id", userConfig.id)
                    p.enabled?.let { put("enabled", it) }
                    p.cronExpr?.let { put("cron_expr", it) }
                    p.freshnessWindowSec?.let { put("freshness_window_sec", it) }
                }.toString(),
                createdAt = System.currentTimeMillis() / 1000L,
            )
        )
    }

    return buildJsonObject { put("success", true) }.toValidJson()
}

@Serializable
data class MaterialCategorySyncUserConfigUpdateRequest(
    @SerialName("user_config_id") val userConfigId: String,
    val enabled: Boolean? = null,
    @SerialName("cron_expr") val cronExpr: String? = null,
    @SerialName("freshness_window_sec") val freshnessWindowSec: Int? = null,
)
