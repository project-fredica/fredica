package com.github.project_fredica.material_category.service

import com.github.project_fredica.material_category.db.MaterialCategorySyncUserConfigRepo

object MaterialCategorySyncUserConfigService {
    private var _repo: MaterialCategorySyncUserConfigRepo? = null

    val repo: MaterialCategorySyncUserConfigRepo
        get() = _repo ?: throw IllegalStateException("MaterialCategorySyncUserConfigService not initialized")

    fun initialize(repo: MaterialCategorySyncUserConfigRepo) {
        _repo = repo
    }
}
