package com.github.project_fredica.material_category.service

import com.github.project_fredica.material_category.db.MaterialCategoryRepo

object MaterialCategoryService {
    private var _repo: MaterialCategoryRepo? = null

    val repo: MaterialCategoryRepo
        get() = _repo ?: throw IllegalStateException("MaterialCategoryService not initialized")

    fun initialize(repo: MaterialCategoryRepo) {
        _repo = repo
    }
}
