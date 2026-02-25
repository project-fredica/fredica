package com.github.project_fredica.db

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MaterialCategory(
    val id: String,
    val name: String,
    val description: String,
    /** Number of materials (any type) associated with this category. */
    @SerialName("material_count") val materialCount: Int = 0,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
)

interface MaterialCategoryRepo {
    /** List all categories with their material counts. */
    suspend fun listAll(): List<MaterialCategory>
    /** Create category if name does not exist, otherwise return existing one. */
    suspend fun createOrGet(name: String, description: String = ""): MaterialCategory
    /** Delete category and all its junction entries. */
    suspend fun deleteById(id: String)
    /** Link material IDs to category IDs in the junction table. */
    suspend fun linkMaterials(materialIds: List<String>, categoryIds: List<String>)
    /** Replace all category associations for a single material. */
    suspend fun setMaterialCategories(materialId: String, categoryIds: List<String>)
}

object MaterialCategoryService {
    private var _repo: MaterialCategoryRepo? = null

    val repo: MaterialCategoryRepo
        get() = _repo ?: throw IllegalStateException("MaterialCategoryService not initialized")

    fun initialize(repo: MaterialCategoryRepo) {
        _repo = repo
    }
}
