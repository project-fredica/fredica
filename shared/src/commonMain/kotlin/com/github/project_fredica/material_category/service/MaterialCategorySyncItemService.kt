package com.github.project_fredica.material_category.service

import com.github.project_fredica.material_category.db.MaterialCategorySyncItemRepo

object MaterialCategorySyncItemService {
    private var _repo: MaterialCategorySyncItemRepo? = null

    val repo: MaterialCategorySyncItemRepo
        get() = _repo ?: throw IllegalStateException("MaterialCategorySyncItemService not initialized")

    fun initialize(repo: MaterialCategorySyncItemRepo) {
        _repo = repo
    }
}
