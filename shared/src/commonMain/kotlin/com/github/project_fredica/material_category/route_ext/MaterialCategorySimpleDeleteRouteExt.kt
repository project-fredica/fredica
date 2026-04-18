package com.github.project_fredica.material_category.route_ext

import com.github.project_fredica.api.routes.MaterialCategorySimpleDeleteRoute
import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.auth.AuthIdentity
import com.github.project_fredica.material_category.model.MaterialCategoryAuditLog
import com.github.project_fredica.material_category.model.MaterialCategoryDefaults
import com.github.project_fredica.material_category.service.MaterialCategoryAuditLogService
import com.github.project_fredica.material_category.service.MaterialCategoryService
import com.github.project_fredica.material_category.service.MaterialCategorySyncPlatformInfoService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

@Suppress("UnusedReceiverParameter")
suspend fun MaterialCategorySimpleDeleteRoute.handler2(param: String, context: RouteContext): ValidJsonString {
    val userId = context.userId ?: return buildJsonObject { put("error", "未登录") }.toValidJson()
    val isRoot = context.identity is AuthIdentity.RootUser
    val p = param.loadJsonModel<MaterialCategorySimpleDeleteRequest>().getOrThrow()

    if (MaterialCategoryDefaults.isUncategorized(p.id)) {
        return buildJsonObject { put("error", "默认分类不可删除") }.toValidJson()
    }

    val category = MaterialCategoryService.repo.getById(p.id)
        ?: return buildJsonObject { put("error", "分类不存在") }.toValidJson()

    if (category.ownerId != userId && !isRoot) {
        return buildJsonObject { put("error", "权限不足") }.toValidJson()
    }

    val syncInfo = MaterialCategorySyncPlatformInfoService.repo.findByCategoryId(p.id)
    if (syncInfo != null) {
        return buildJsonObject { put("error", "该分类是同步分类，请使用 MaterialCategorySyncDeleteRoute") }.toValidJson()
    }

    MaterialCategoryService.repo.ensureUncategorized(category.ownerId)
    val orphanIds = MaterialCategoryService.repo.findOrphanMaterialIds(p.id)
    val deleted = MaterialCategoryService.repo.deleteById(p.id, category.ownerId)
    if (!deleted) return buildJsonObject { put("error", "删除失败") }.toValidJson()

    if (orphanIds.isNotEmpty()) {
        val uncatId = MaterialCategoryDefaults.uncategorizedId(category.ownerId)
        MaterialCategoryService.repo.linkMaterials(orphanIds, listOf(uncatId))
    }

    MaterialCategoryAuditLogService.repo.insert(
        MaterialCategoryAuditLog(
            id = UUID.randomUUID().toString(),
            categoryId = p.id,
            userId = userId,
            action = "simple_delete",
            detail = buildJsonObject { put("name", category.name) }.toString(),
            createdAt = System.currentTimeMillis() / 1000L,
        )
    )

    return buildJsonObject { put("success", true) }.toValidJson()
}

@Serializable
data class MaterialCategorySimpleDeleteRequest(
    val id: String,
)
