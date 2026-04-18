package com.github.project_fredica.material_category.db

import com.github.project_fredica.material_category.model.MaterialCategoryAuditLog

interface MaterialCategoryAuditLogRepo {
    suspend fun insert(log: MaterialCategoryAuditLog)
    suspend fun listByCategoryId(categoryId: String, limit: Int = 50): List<MaterialCategoryAuditLog>
    suspend fun listByUserId(userId: String, limit: Int = 50): List<MaterialCategoryAuditLog>
}
