package com.github.project_fredica.material_category.route_ext

import com.github.project_fredica.api.routes.MaterialCategorySyncCheckRoute
import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.material_category.model.MaterialCategorySyncPlatformIdentity
import com.github.project_fredica.material_category.service.MaterialCategorySyncPlatformInfoService
import com.github.project_fredica.material_category.service.MaterialCategorySyncUserConfigService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Suppress("UnusedReceiverParameter")
suspend fun MaterialCategorySyncCheckRoute.handler2(param: String, context: RouteContext): ValidJsonString {
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

    val existing = MaterialCategorySyncPlatformInfoService.repo
        .findByPlatformKey(identity.syncType, identity.platformId)

    if (existing == null) {
        return buildJsonObject {
            put("exists", false)
            put("subscribed", false)
        }.toValidJson()
    }

    val userConfig = MaterialCategorySyncUserConfigService.repo
        .findByPlatformInfoAndUser(existing.id, userId)

    return buildJsonObject {
        put("exists", true)
        put("subscribed", userConfig != null)
    }.toValidJson()
}
