package com.github.project_fredica.material_category.route_ext

import com.github.project_fredica.api.routes.MaterialCategorySyncDeleteRoute
import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.auth.AuthIdentity
import com.github.project_fredica.material_category.model.MaterialCategoryAuditLog

import com.github.project_fredica.material_category.service.MaterialCategoryAuditLogService
import com.github.project_fredica.material_category.service.MaterialCategoryService
import com.github.project_fredica.material_category.service.MaterialCategorySyncItemService
import com.github.project_fredica.material_category.service.MaterialCategorySyncPlatformInfoService
import com.github.project_fredica.material_category.service.MaterialCategorySyncUserConfigService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

@Suppress("UnusedReceiverParameter")
suspend fun MaterialCategorySyncDeleteRoute.handler2(param: String, context: RouteContext): ValidJsonString {
    val userId = context.userId ?: return buildJsonObject { put("error", "未登录") }.toValidJson()
    val isRoot = context.identity is AuthIdentity.RootUser
    val p = param.loadJsonModel<MaterialCategorySyncDeleteRequest>().getOrThrow()

    val category = MaterialCategoryService.repo.getById(p.id)
        ?: return buildJsonObject { put("error", "分类不存在") }.toValidJson()

    if (category.ownerId != userId && !isRoot) {
        return buildJsonObject { put("error", "权限不足") }.toValidJson()
    }

    val syncInfo = MaterialCategorySyncPlatformInfoService.repo.findByCategoryId(p.id)
    if (syncInfo == null) {
        return buildJsonObject { put("error", "该分类不是同步分类") }.toValidJson()
    }

    if (syncInfo.syncState == "syncing") {
        return buildJsonObject { put("error", "同步任务正在运行，请等待完成后再删除") }.toValidJson()
    }

    // Cascade delete sync metadata
    MaterialCategorySyncItemService.repo.deleteByPlatformInfoId(syncInfo.id)
    MaterialCategorySyncUserConfigService.repo.deleteByPlatformInfoId(syncInfo.id)
    MaterialCategorySyncPlatformInfoService.repo.deleteById(syncInfo.id)

    // Handle orphan materials
    MaterialCategoryService.repo.reconcileOrphanMaterials(p.id, category.ownerId)
    val deleted = MaterialCategoryService.repo.deleteById(p.id, category.ownerId)
    if (!deleted) return buildJsonObject { put("error", "删除失败") }.toValidJson()

    MaterialCategoryAuditLogService.repo.insert(
        MaterialCategoryAuditLog(
            id = UUID.randomUUID().toString(),
            categoryId = p.id,
            userId = userId,
            action = "sync_delete",
            detail = buildJsonObject {
                put("name", category.name)
                put("platform_info_id", syncInfo.id)
                put("sync_type", syncInfo.syncType)
            }.toString(),
            createdAt = System.currentTimeMillis() / 1000L,
        )
    )

    return buildJsonObject { put("success", true) }.toValidJson()
}

@Serializable
data class MaterialCategorySyncDeleteRequest(
    val id: String,
)
