package com.github.project_fredica.material_category.service

import com.github.project_fredica.material_category.db.MaterialCategorySyncPlatformInfoRepo

object MaterialCategorySyncPlatformInfoService {
    private var _repo: MaterialCategorySyncPlatformInfoRepo? = null

    val repo: MaterialCategorySyncPlatformInfoRepo
        get() = _repo ?: throw IllegalStateException("MaterialCategorySyncPlatformInfoService not initialized")

    fun initialize(repo: MaterialCategorySyncPlatformInfoRepo) {
        _repo = repo
    }
}
