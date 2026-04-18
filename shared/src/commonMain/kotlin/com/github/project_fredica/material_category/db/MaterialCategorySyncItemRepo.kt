package com.github.project_fredica.material_category.db

import com.github.project_fredica.material_category.model.MaterialCategorySyncItem

interface MaterialCategorySyncItemRepo {
    suspend fun upsert(item: MaterialCategorySyncItem)
    suspend fun upsertBatch(items: List<MaterialCategorySyncItem>)
    suspend fun listByPlatformInfo(platformInfoId: String): List<MaterialCategorySyncItem>
    suspend fun countByPlatformInfo(platformInfoId: String): Int
    suspend fun deleteByPlatformInfoId(platformInfoId: String): Int
}
