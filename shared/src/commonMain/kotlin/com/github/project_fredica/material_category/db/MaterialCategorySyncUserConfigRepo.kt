package com.github.project_fredica.material_category.db

import com.github.project_fredica.material_category.model.MaterialCategorySyncUserConfig

interface MaterialCategorySyncUserConfigRepo {
    suspend fun getById(id: String): MaterialCategorySyncUserConfig?
    suspend fun findByPlatformInfoAndUser(platformInfoId: String, userId: String): MaterialCategorySyncUserConfig?
    suspend fun listByPlatformInfo(platformInfoId: String): List<MaterialCategorySyncUserConfig>
    suspend fun listByUser(userId: String): List<MaterialCategorySyncUserConfig>
    suspend fun subscriberCount(platformInfoId: String): Int
    suspend fun create(config: MaterialCategorySyncUserConfig)
    suspend fun deleteById(id: String): Boolean
    suspend fun deleteByPlatformInfoId(platformInfoId: String): Int
    suspend fun update(
        id: String,
        enabled: Boolean? = null,
        cronExpr: String? = null,
        freshnessWindowSec: Int? = null,
    ): Boolean
}
