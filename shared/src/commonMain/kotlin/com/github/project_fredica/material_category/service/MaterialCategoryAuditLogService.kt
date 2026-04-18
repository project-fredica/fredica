package com.github.project_fredica.material_category.service

import com.github.project_fredica.material_category.db.MaterialCategoryAuditLogRepo

object MaterialCategoryAuditLogService {
    private var _repo: MaterialCategoryAuditLogRepo? = null

    val repo: MaterialCategoryAuditLogRepo
        get() = _repo ?: throw IllegalStateException("MaterialCategoryAuditLogService not initialized")

    fun initialize(repo: MaterialCategoryAuditLogRepo) {
        _repo = repo
    }
}
