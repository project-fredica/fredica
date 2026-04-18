package com.github.project_fredica.material_category.route_ext

import com.github.project_fredica.api.routes.MaterialCategorySyncListRoute
import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.material_category.model.MaterialCategorySyncPlatformInfo
import com.github.project_fredica.material_category.model.MaterialCategorySyncUserConfig
import com.github.project_fredica.material_category.service.MaterialCategorySyncPlatformInfoService
import com.github.project_fredica.material_category.service.MaterialCategorySyncUserConfigService
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Suppress("UnusedReceiverParameter")
suspend fun MaterialCategorySyncListRoute.handler2(param: String, context: RouteContext): ValidJsonString {
    val userId = context.userId ?: return buildJsonObject { put("error", "未登录") }.toValidJson()

    val myConfigs = MaterialCategorySyncUserConfigService.repo.listByUser(userId)

    val result = buildJsonArray {
        for (config in myConfigs) {
            val platformInfo = MaterialCategorySyncPlatformInfoService.repo.getById(config.platformInfoId)
                ?: continue
            val subscriberCount = MaterialCategorySyncUserConfigService.repo.subscriberCount(platformInfo.id)
            add(buildJsonObject {
                put("platform_info", AppUtil.GlobalVars.json.encodeToJsonElement(
                    MaterialCategorySyncPlatformInfo.serializer(), platformInfo
                ))
                put("user_config", AppUtil.GlobalVars.json.encodeToJsonElement(
                    MaterialCategorySyncUserConfig.serializer(), config
                ))
                put("subscriber_count", subscriberCount)
            })
        }
    }

    return ValidJsonString(result.toString())
}
