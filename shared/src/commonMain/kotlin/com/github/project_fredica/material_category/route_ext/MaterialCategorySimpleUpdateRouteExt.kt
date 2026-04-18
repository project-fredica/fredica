package com.github.project_fredica.material_category.route_ext

import com.github.project_fredica.api.routes.MaterialCategorySimpleUpdateRoute
import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.material_category.model.MaterialCategory
import com.github.project_fredica.auth.AuthIdentity
import com.github.project_fredica.material_category.model.MaterialCategoryAuditLog
import com.github.project_fredica.material_category.model.MaterialCategoryDefaults
import com.github.project_fredica.material_category.service.MaterialCategoryAuditLogService
import com.github.project_fredica.material_category.service.MaterialCategoryService
import com.github.project_fredica.material_category.service.MaterialCategorySyncPlatformInfoService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

@Suppress("UnusedReceiverParameter")
suspend fun MaterialCategorySimpleUpdateRoute.handler2(param: String, context: RouteContext): ValidJsonString {
    val userId = context.userId ?: return buildJsonObject { put("error", "未登录") }.toValidJson()
    val isRoot = context.identity is AuthIdentity.RootUser
    val p = param.loadJsonModel<MaterialCategorySimpleUpdateRequest>().getOrThrow()

    val category = MaterialCategoryService.repo.getById(p.id)
        ?: return buildJsonObject { put("error", "分类不存在") }.toValidJson()

    if (category.ownerId != userId && !isRoot) {
        return buildJsonObject { put("error", "权限不足") }.toValidJson()
    }

    val syncInfo = MaterialCategorySyncPlatformInfoService.repo.findByCategoryId(p.id)
    if (syncInfo != null) {
        return buildJsonObject { put("error", "该分类是同步分类，请使用 MaterialCategorySyncUpdateRoute") }.toValidJson()
    }

    val isUncategorized = MaterialCategoryDefaults.isUncategorized(p.id)
    if (isUncategorized && p.name != null) {
        return buildJsonObject { put("error", "默认分类不可重命名") }.toValidJson()
    }
    if (isUncategorized && (p.allowOthersView != null || p.allowOthersAdd != null || p.allowOthersDelete != null)) {
        return buildJsonObject { put("error", "默认分类权限设置不可修改") }.toValidJson()
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
        allowOthersView = p.allowOthersView,
        allowOthersAdd = p.allowOthersAdd,
        allowOthersDelete = p.allowOthersDelete,
    )
    if (!updated) return buildJsonObject { put("error", "同名分类已存在") }.toValidJson()

    val updatedCategory = MaterialCategoryService.repo.getById(p.id)!!

    MaterialCategoryAuditLogService.repo.insert(
        MaterialCategoryAuditLog(
            id = UUID.randomUUID().toString(),
            categoryId = p.id,
            userId = userId,
            action = "simple_update",
            detail = buildJsonObject {
                p.name?.let { put("name", it.trim()) }
                p.description?.let { put("description", it) }
                p.allowOthersView?.let { put("allow_others_view", it) }
                p.allowOthersAdd?.let { put("allow_others_add", it) }
                p.allowOthersDelete?.let { put("allow_others_delete", it) }
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
data class MaterialCategorySimpleUpdateRequest(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    @SerialName("allow_others_view") val allowOthersView: Boolean? = null,
    @SerialName("allow_others_add") val allowOthersAdd: Boolean? = null,
    @SerialName("allow_others_delete") val allowOthersDelete: Boolean? = null,
)
