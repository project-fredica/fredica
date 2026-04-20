package com.github.project_fredica.material_category.db

import com.github.project_fredica.material_category.model.MaterialCategory
import com.github.project_fredica.material_category.model.MaterialCategoryFilter

interface MaterialCategoryRepo {
    suspend fun listForUser(userId: String): List<MaterialCategory>
    suspend fun listMine(userId: String): List<MaterialCategory>
    suspend fun listFiltered(
        userId: String?,
        filter: MaterialCategoryFilter = MaterialCategoryFilter.ALL,
        search: String? = null,
        offset: Int = 0,
        limit: Int = 50,
    ): Pair<List<MaterialCategory>, Int>
    suspend fun getById(id: String): MaterialCategory?
    suspend fun create(ownerId: String, name: String, description: String = ""): MaterialCategory
    suspend fun insertOrIgnore(category: MaterialCategory)
    suspend fun update(
        categoryId: String,
        userId: String,
        name: String? = null,
        description: String? = null,
        allowOthersView: Boolean? = null,
        allowOthersAdd: Boolean? = null,
        allowOthersDelete: Boolean? = null,
    ): Boolean
    suspend fun deleteById(categoryId: String, userId: String): Boolean
    suspend fun linkMaterials(materialIds: List<String>, categoryIds: List<String>, addedBy: String = "user")
    suspend fun setMaterialCategories(materialId: String, categoryIds: List<String>, addedBy: String = "user")
    suspend fun deleteByMaterialIdExcluding(materialId: String, excludeCategoryIds: List<String>, onlyAddedBy: String? = "user")
    suspend fun findOrphanMaterialIds(categoryId: String): List<String>
    suspend fun ensureUncategorized(userId: String)
    suspend fun reconcileOrphanMaterials(categoryId: String, ownerId: String)
    suspend fun reconcileAllOrphanMaterials()
}
