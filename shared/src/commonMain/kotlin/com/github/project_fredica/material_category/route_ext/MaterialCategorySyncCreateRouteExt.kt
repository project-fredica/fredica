package com.github.project_fredica.material_category.route_ext

import com.github.project_fredica.api.routes.MaterialCategorySyncCreateRoute
import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.material_category.model.MaterialCategory
import com.github.project_fredica.material_category.model.MaterialCategoryAuditLog
import com.github.project_fredica.material_category.model.MaterialCategorySyncPlatformIdentity
import com.github.project_fredica.material_category.model.MaterialCategorySyncPlatformInfo
import com.github.project_fredica.material_category.model.MaterialCategorySyncUserConfig
import com.github.project_fredica.material_category.service.MaterialCategoryAuditLogService
import com.github.project_fredica.material_category.service.MaterialCategoryService
import com.github.project_fredica.material_category.service.MaterialCategorySyncPlatformInfoService
import com.github.project_fredica.material_category.service.MaterialCategorySyncUserConfigService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

@Suppress("UnusedReceiverParameter")
suspend fun MaterialCategorySyncCreateRoute.handler2(param: String, context: RouteContext): ValidJsonString {
    val userId = context.userId ?: return buildJsonObject { put("error", "未登录") }.toValidJson()
    val p = param.loadJsonModel<MaterialCategorySyncCreateRequest>().getOrThrow()

    val identity: MaterialCategorySyncPlatformIdentity = try {
        AppUtil.GlobalVars.json.decodeFromJsonElement(
            MaterialCategorySyncPlatformIdentity.serializer(),
            buildJsonObject {
                put("type", p.syncType)
                p.platformConfig.forEach { (k, v) -> put(k, v) }
            }
        )
    } catch (_: Exception) {
        return buildJsonObject { put("error", "请求参数无效") }.toValidJson()
    }

    if (p.displayName != null && p.displayName.length > 64) {
        return buildJsonObject { put("error", "display_name 过长") }.toValidJson()
    }

    val existingPlatform = MaterialCategorySyncPlatformInfoService.repo
        .findByPlatformKey(identity.syncType, identity.platformId)

    if (existingPlatform != null) {
        val existingConfig = MaterialCategorySyncUserConfigService.repo
            .findByPlatformInfoAndUser(existingPlatform.id, userId)
        if (existingConfig != null) {
            return buildJsonObject {
                put("error", "已订阅该数据源")
                put("existing_user_config_id", existingConfig.id)
            }.toValidJson()
        }
    }

    val isNewPlatform = existingPlatform == null
    val nowSec = System.currentTimeMillis() / 1000L

    val platformInfo = existingPlatform ?: run {
        val categoryId = UUID.randomUUID().toString()
        val platformInfoId = UUID.randomUUID().toString()
        val autoName = generateSyncCategoryName(identity)
        val displayName = p.displayName ?: autoName

        MaterialCategoryService.repo.insertOrIgnore(
            MaterialCategory(
                id = categoryId,
                ownerId = userId,
                name = autoName,
                description = "",
                allowOthersView = true,
                createdAt = nowSec,
                updatedAt = nowSec,
            )
        )

        val info = MaterialCategorySyncPlatformInfo(
            id = platformInfoId,
            syncType = identity.syncType,
            platformId = identity.platformId,
            platformConfig = AppUtil.GlobalVars.json.encodeToString(
                MaterialCategorySyncPlatformIdentity.serializer(), identity
            ),
            displayName = displayName,
            categoryId = categoryId,
            syncState = "idle",
            createdAt = nowSec,
            updatedAt = nowSec,
        )
        MaterialCategorySyncPlatformInfoService.repo.create(info)
        info
    }

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
                put("sync_type", identity.syncType)
            }.toString(),
            createdAt = nowSec,
        )
    )

    val category = MaterialCategoryService.repo.getById(platformInfo.categoryId)

    return buildJsonObject {
        put("platform_info", AppUtil.GlobalVars.json.encodeToJsonElement(
            MaterialCategorySyncPlatformInfo.serializer(), platformInfo
        ))
        put("user_config", AppUtil.GlobalVars.json.encodeToJsonElement(
            MaterialCategorySyncUserConfig.serializer(), userConfig
        ))
        if (category != null) {
            put("category", AppUtil.GlobalVars.json.encodeToJsonElement(
                MaterialCategory.serializer(), category
            ))
        }
        put("is_new_platform", isNewPlatform)
    }.toValidJson()
}

private fun generateSyncCategoryName(identity: MaterialCategorySyncPlatformIdentity): String = when (identity) {
    is MaterialCategorySyncPlatformIdentity.BilibiliFavorite -> "[B站收藏夹] ${identity.mediaId}"
    is MaterialCategorySyncPlatformIdentity.BilibiliUploader -> "[B站UP主] ${identity.mid}"
    is MaterialCategorySyncPlatformIdentity.BilibiliSeason -> "[B站合集] ${identity.seasonId}"
    is MaterialCategorySyncPlatformIdentity.BilibiliSeries -> "[B站列表] ${identity.seriesId}"
    is MaterialCategorySyncPlatformIdentity.BilibiliVideoPages -> "[B站分P] ${identity.bvid}"
}

@Serializable
data class MaterialCategorySyncCreateRequest(
    @SerialName("sync_type") val syncType: String,
    @SerialName("platform_config") val platformConfig: JsonObject,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("cron_expr") val cronExpr: String? = null,
    @SerialName("freshness_window_sec") val freshnessWindowSec: Int? = null,
)
