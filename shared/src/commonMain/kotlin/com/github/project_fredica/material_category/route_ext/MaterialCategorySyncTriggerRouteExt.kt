package com.github.project_fredica.material_category.route_ext

import com.github.project_fredica.api.routes.MaterialCategorySyncTriggerRoute
import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.material_category.service.MaterialCategorySyncTriggerService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Suppress("UnusedReceiverParameter")
suspend fun MaterialCategorySyncTriggerRoute.handler2(param: String, context: RouteContext): ValidJsonString {
    val userId = context.userId ?: return buildJsonObject { put("error", "未登录") }.toValidJson()
    val p = param.loadJsonModel<MaterialCategorySyncTriggerRequest>().getOrThrow()

    return when (val result = MaterialCategorySyncTriggerService.trigger(p.platformInfoId, userId)) {
        is MaterialCategorySyncTriggerService.TriggerResult.Success -> buildJsonObject {
            put("success", true)
            put("workflow_run_id", result.workflowRunId)
        }.toValidJson()
        is MaterialCategorySyncTriggerService.TriggerResult.Error -> buildJsonObject {
            put("error", result.message)
        }.toValidJson()
    }
}

@Serializable
data class MaterialCategorySyncTriggerRequest(
    @SerialName("platform_info_id") val platformInfoId: String,
)
