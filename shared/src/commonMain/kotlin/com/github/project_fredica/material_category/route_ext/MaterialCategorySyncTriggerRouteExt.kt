package com.github.project_fredica.material_category.route_ext

import com.github.project_fredica.api.routes.MaterialCategorySyncTriggerRoute
import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
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
suspend fun MaterialCategorySyncTriggerRoute.handler2(param: String, context: RouteContext): ValidJsonString {
    val userId = context.userId ?: return buildJsonObject { put("error", "未登录") }.toValidJson()
    val p = param.loadJsonModel<MaterialCategorySyncTriggerRequest>().getOrThrow()

    val platformInfo = MaterialCategorySyncPlatformInfoService.repo.getById(p.platformInfoId)
        ?: return buildJsonObject { put("error", "同步源不存在") }.toValidJson()

    val userConfig = MaterialCategorySyncUserConfigService.repo
        .findByPlatformInfoAndUser(platformInfo.id, userId)
    if (userConfig == null) {
        return buildJsonObject { put("error", "未订阅该同步源") }.toValidJson()
    }

    if (platformInfo.syncState == "syncing") {
        return buildJsonObject { put("error", "同步任务正在运行") }.toValidJson()
    }

    MaterialCategorySyncPlatformInfoService.repo.setSyncState(platformInfo.id, "pending")

    MaterialCategoryAuditLogService.repo.insert(
        MaterialCategoryAuditLog(
            id = UUID.randomUUID().toString(),
            categoryId = platformInfo.categoryId,
            userId = userId,
            action = "sync_trigger",
            detail = buildJsonObject {
                put("platform_info_id", platformInfo.id)
                put("sync_type", platformInfo.syncType)
            }.toString(),
            createdAt = System.currentTimeMillis() / 1000L,
        )
    )

    return buildJsonObject {
        put("success", true)
        put("sync_state", "pending")
    }.toValidJson()
}

@Serializable
data class MaterialCategorySyncTriggerRequest(
    @SerialName("platform_info_id") val platformInfoId: String,
)
