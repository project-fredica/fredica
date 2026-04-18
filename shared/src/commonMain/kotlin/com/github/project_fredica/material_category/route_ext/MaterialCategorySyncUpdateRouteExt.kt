package com.github.project_fredica.material_category.route_ext

import com.github.project_fredica.api.routes.MaterialCategorySyncUpdateRoute
import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.auth.AuthIdentity
import com.github.project_fredica.material_category.model.MaterialCategory
import com.github.project_fredica.material_category.model.MaterialCategoryAuditLog
import com.github.project_fredica.material_category.service.MaterialCategoryAuditLogService
import com.github.project_fredica.material_category.service.MaterialCategoryService
import com.github.project_fredica.material_category.service.MaterialCategorySyncPlatformInfoService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

@Suppress("UnusedReceiverParameter")
suspend fun MaterialCategorySyncUpdateRoute.handler2(param: String, context: RouteContext): ValidJsonString {
    val userId = context.userId ?: return buildJsonObject { put("error", "未登录") }.toValidJson()
    val isRoot = context.identity is AuthIdentity.RootUser
    val p = param.loadJsonModel<MaterialCategorySyncUpdateRequest>().getOrThrow()

    val category = MaterialCategoryService.repo.getById(p.id)
        ?: return buildJsonObject { put("error", "分类不存在") }.toValidJson()

    if (category.ownerId != userId && !isRoot) {
        return buildJsonObject { put("error", "权限不足") }.toValidJson()
    }

    val syncInfo = MaterialCategorySyncPlatformInfoService.repo.findByCategoryId(p.id)
    if (syncInfo == null) {
        return buildJsonObject { put("error", "该分类不是同步分类") }.toValidJson()
    }

    if (p.name != null) {
        val trimmed = p.name.trim()
        if (trimmed.isBlank()) return buildJsonObject { put("error", "分类名称不能为空") }.toValidJson()
        if (trimmed.length > 64) return buildJsonObject { put("error", "分类名称过长") }.toValidJson()
    }

    val updated = MaterialCategoryService.repo.update(
        categoryId = p.id,
        userId = category.ownerId,
        name = p.name?.trim(),
        description = p.description,
    )
    if (!updated) return buildJsonObject { put("error", "同名分类已存在") }.toValidJson()

    val updatedCategory = MaterialCategoryService.repo.getById(p.id)!!

    MaterialCategoryAuditLogService.repo.insert(
        MaterialCategoryAuditLog(
            id = UUID.randomUUID().toString(),
            categoryId = p.id,
            userId = userId,
            action = "sync_update",
            detail = buildJsonObject {
                p.name?.let { put("name", it.trim()) }
                p.description?.let { put("description", it) }
            }.toString(),
            createdAt = System.currentTimeMillis() / 1000L,
        )
    )

    return buildJsonObject {
        put("success", true)
        put("category", AppUtil.GlobalVars.json.encodeToJsonElement(MaterialCategory.serializer(), updatedCategory))
    }.toValidJson()
}

@Serializable
data class MaterialCategorySyncUpdateRequest(
    val id: String,
    val name: String? = null,
    val description: String? = null,
)
