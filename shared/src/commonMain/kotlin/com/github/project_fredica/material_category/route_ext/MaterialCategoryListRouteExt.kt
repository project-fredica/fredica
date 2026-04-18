package com.github.project_fredica.material_category.route_ext

import com.github.project_fredica.api.routes.MaterialCategoryListRoute
import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.material_category.model.MaterialCategoryFilter
import com.github.project_fredica.material_category.model.MaterialCategoryListRequest
import com.github.project_fredica.material_category.model.MaterialCategoryListResponse
import com.github.project_fredica.material_category.model.MaterialCategorySyncPlatformIdentity
import com.github.project_fredica.material_category.model.MaterialCategorySyncPlatformInfoSummary
import com.github.project_fredica.material_category.model.MaterialCategorySyncUserConfigSummary
import com.github.project_fredica.material_category.service.MaterialCategoryService
import com.github.project_fredica.material_category.service.MaterialCategorySyncPlatformInfoService
import com.github.project_fredica.material_category.service.MaterialCategorySyncUserConfigService

@Suppress("UnusedReceiverParameter")
suspend fun MaterialCategoryListRoute.handler2(param: String, context: RouteContext): ValidJsonString {
    val userId = context.userId
    val p = param.loadJsonModel<MaterialCategoryListRequest>().getOrElse {
        MaterialCategoryListRequest()
    }

    val (rawItems, total) = MaterialCategoryService.repo.listFiltered(
        userId = userId,
        filter = p.filter,
        search = p.search,
        offset = p.offset,
        limit = p.limit,
    )

    val enrichedItems = rawItems.map { cat ->
        val platformInfo = MaterialCategorySyncPlatformInfoService.repo.findByCategoryId(cat.id)
        if (platformInfo != null) {
            val parsedConfig = try {
                AppUtil.GlobalVars.json.decodeFromString(
                    MaterialCategorySyncPlatformIdentity.serializer(),
                    platformInfo.platformConfig,
                )
            } catch (_: Exception) {
                null
            }
            val subscriberCount = MaterialCategorySyncUserConfigService.repo.subscriberCount(platformInfo.id)
            val myConfig = if (userId != null) {
                MaterialCategorySyncUserConfigService.repo.findByPlatformInfoAndUser(platformInfo.id, userId)
            } else null

            cat.copy(
                sync = MaterialCategorySyncPlatformInfoSummary(
                    id = platformInfo.id,
                    syncType = platformInfo.syncType,
                    platformConfig = parsedConfig ?: return@map cat,
                    displayName = platformInfo.displayName,
                    lastSyncedAt = platformInfo.lastSyncedAt,
                    itemCount = platformInfo.itemCount,
                    syncState = platformInfo.syncState,
                    lastError = platformInfo.lastError,
                    failCount = platformInfo.failCount,
                    subscriberCount = subscriberCount,
                    mySubscription = myConfig?.let {
                        MaterialCategorySyncUserConfigSummary(
                            id = it.id,
                            enabled = it.enabled,
                            cronExpr = it.cronExpr,
                            freshnessWindowSec = it.freshnessWindowSec,
                        )
                    },
                    ownerId = cat.ownerId,
                )
            )
        } else {
            cat
        }
    }

    val response = MaterialCategoryListResponse(
        items = enrichedItems,
        total = total,
        offset = p.offset,
        limit = p.limit,
    )
    return AppUtil.dumpJsonStr(response).getOrThrow()
}
