package com.github.project_fredica.material_category.route_ext

import com.github.project_fredica.api.routes.MaterialCategorySimpleImportRoute
import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.material_category.model.MaterialCategoryDefaults
import com.github.project_fredica.material_category.service.MaterialCategoryService
import com.github.project_fredica.material_category.service.MaterialCategorySyncPlatformInfoService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Suppress("UnusedReceiverParameter")
suspend fun MaterialCategorySimpleImportRoute.handler2(param: String, context: RouteContext): ValidJsonString {
    val userId = context.userId ?: return buildJsonObject { put("error", "未登录") }.toValidJson()
    val p = param.loadJsonModel<MaterialCategorySimpleImportRequest>().getOrThrow()

    if (p.materialIds.isEmpty()) {
        return buildJsonObject { put("error", "素材列表不能为空") }.toValidJson()
    }

    if (p.categoryIds.isEmpty()) {
        return buildJsonObject { put("error", "分类列表不能为空") }.toValidJson()
    }

    for (catId in p.categoryIds) {
        val category = MaterialCategoryService.repo.getById(catId)
            ?: return buildJsonObject { put("error", "分类不存在: $catId") }.toValidJson()

        if (category.ownerId != userId) {
            if (!category.allowOthersAdd) {
                return buildJsonObject { put("error", "无权向分类 ${category.name} 添加素材") }.toValidJson()
            }
        }

        val syncInfo = MaterialCategorySyncPlatformInfoService.repo.findByCategoryId(catId)
        if (syncInfo != null) {
            return buildJsonObject { put("error", "不能手动向同步分类添加素材") }.toValidJson()
        }
    }

    MaterialCategoryService.repo.ensureUncategorized(userId)

    if (p.replaceExisting) {
        for (materialId in p.materialIds) {
            MaterialCategoryService.repo.setMaterialCategories(
                materialId = materialId,
                categoryIds = p.categoryIds,
                addedBy = userId,
            )
        }
    } else {
        MaterialCategoryService.repo.linkMaterials(
            materialIds = p.materialIds,
            categoryIds = p.categoryIds,
            addedBy = userId,
        )
    }

    return buildJsonObject {
        put("success", true)
        put("material_count", p.materialIds.size)
        put("category_count", p.categoryIds.size)
    }.toValidJson()
}

@Serializable
data class MaterialCategorySimpleImportRequest(
    @SerialName("material_ids") val materialIds: List<String>,
    @SerialName("category_ids") val categoryIds: List<String>,
    @SerialName("replace_existing") val replaceExisting: Boolean = false,
)
