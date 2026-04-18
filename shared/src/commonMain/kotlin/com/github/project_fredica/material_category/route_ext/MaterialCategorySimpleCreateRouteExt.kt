package com.github.project_fredica.material_category.route_ext

import com.github.project_fredica.api.routes.MaterialCategorySimpleCreateRoute
import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.material_category.model.MaterialCategory
import com.github.project_fredica.material_category.service.MaterialCategoryService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Suppress("UnusedReceiverParameter")
suspend fun MaterialCategorySimpleCreateRoute.handler2(param: String, context: RouteContext): ValidJsonString {
    val userId = context.userId ?: return buildJsonObject { put("error", "未登录") }.toValidJson()
    val p = param.loadJsonModel<MaterialCategorySimpleCreateRequest>().getOrThrow()
    val name = p.name.trim()
    if (name.isBlank()) return buildJsonObject { put("error", "分类名称不能为空") }.toValidJson()
    if (name.length > 64) return buildJsonObject { put("error", "分类名称过长") }.toValidJson()
    MaterialCategoryService.repo.ensureUncategorized(userId)
    val category: MaterialCategory = MaterialCategoryService.repo.create(userId, name, p.description)
    return AppUtil.dumpJsonStr(category).getOrThrow()
}

@Serializable
data class MaterialCategorySimpleCreateRequest(
    val name: String,
    val description: String = "",
)
