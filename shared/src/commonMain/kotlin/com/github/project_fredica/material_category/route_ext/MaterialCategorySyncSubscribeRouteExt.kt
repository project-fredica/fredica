package com.github.project_fredica.material_category.route_ext

import com.github.project_fredica.api.routes.MaterialCategorySyncSubscribeRoute
import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.material_category.model.MaterialCategoryAuditLog
import com.github.project_fredica.material_category.model.MaterialCategorySyncPlatformInfo
import com.github.project_fredica.material_category.model.MaterialCategorySyncUserConfig
import com.github.project_fredica.material_category.service.MaterialCategoryAuditLogService
import com.github.project_fredica.material_category.service.MaterialCategorySyncPlatformInfoService
import com.github.project_fredica.material_category.service.MaterialCategorySyncUserConfigService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

@Suppress("UnusedReceiverParameter")
suspend fun MaterialCategorySyncSubscribeRoute.handler2(param: String, context: RouteContext): ValidJsonString {
    val userId = context.userId ?: return buildJsonObject { put("error", "未登录") }.toValidJson()
    val p = param.loadJsonModel<MaterialCategorySyncSubscribeRequest>().getOrThrow()

    val platformInfo = MaterialCategorySyncPlatformInfoService.repo.getById(p.platformInfoId)
        ?: return buildJsonObject { put("error", "同步源不存在") }.toValidJson()

    val existing = MaterialCategorySyncUserConfigService.repo
        .findByPlatformInfoAndUser(platformInfo.id, userId)
    if (existing != null) {
        return buildJsonObject {
            put("error", "已订阅该数据源")
            put("existing_user_config_id", existing.id)
        }.toValidJson()
    }

    val nowSec = System.currentTimeMillis() / 1000L
    val userConfigId = UUID.randomUUID().toString()
    val userConfig = MaterialCategorySyncUserConfig(
        id = userConfigId,
        platformInfoId = platformInfo.id,
        userId = userId,
        enabled = true,
        cronExpr = p.cronExpr ?: "",
        freshnessWindowSec = p.freshnessWindowSec ?: 0,
        createdAt = nowSec,
        updatedAt = nowSec,
    )
    MaterialCategorySyncUserConfigService.repo.create(userConfig)

    MaterialCategoryAuditLogService.repo.insert(
        MaterialCategoryAuditLog(
            id = UUID.randomUUID().toString(),
            categoryId = platformInfo.categoryId,
            userId = userId,
            action = "sync_subscribe",
            detail = buildJsonObject {
                put("platform_info_id", platformInfo.id)
                put("sync_type", platformInfo.syncType)
            }.toString(),
            createdAt = nowSec,
        )
    )

    return buildJsonObject {
        put("user_config", AppUtil.GlobalVars.json.encodeToJsonElement(
            MaterialCategorySyncUserConfig.serializer(), userConfig
        ))
        put("platform_info", AppUtil.GlobalVars.json.encodeToJsonElement(
            MaterialCategorySyncPlatformInfo.serializer(), platformInfo
        ))
    }.toValidJson()
}

@Serializable
data class MaterialCategorySyncSubscribeRequest(
    @SerialName("platform_info_id") val platformInfoId: String,
    @SerialName("cron_expr") val cronExpr: String? = null,
    @SerialName("freshness_window_sec") val freshnessWindowSec: Int? = null,
)
