package com.github.project_fredica.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database
import java.util.UUID

/**
 * DB implementation for material categories.
 *
 * Two tables managed here:
 *  - [material_category]     — category definitions (id, name, description)
 *  - [material_category_rel] — M:N junction between any [material] and a category
 *    (replaces the old `material_video_category` table)
 */
class MaterialCategoryDb(private val db: Database) : MaterialCategoryRepo {

    suspend fun initialize() {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                withContext(Dispatchers.IO) {
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS material_category (
                            id          TEXT PRIMARY KEY,
                            name        TEXT NOT NULL UNIQUE,
                            description TEXT NOT NULL DEFAULT '',
                            created_at  INTEGER NOT NULL,
                            updated_at  INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    // Junction table: works for ANY material type
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS material_category_rel (
                            material_id TEXT NOT NULL,
                            category_id TEXT NOT NULL,
                            PRIMARY KEY (material_id, category_id)
                        )
                        """.trimIndent()
                    )
                }
            }
        }
    }

    override suspend fun listAll(): List<MaterialCategory> = withContext(Dispatchers.IO) {
        val result = mutableListOf<MaterialCategory>()
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                SELECT c.id, c.name, c.description, c.created_at, c.updated_at,
                       COUNT(mcr.material_id) AS material_count
                FROM material_category c
                LEFT JOIN material_category_rel mcr ON c.id = mcr.category_id
                GROUP BY c.id
                ORDER BY c.name
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        result.add(
                            MaterialCategory(
                                id = rs.getString("id"),
                                name = rs.getString("name"),
                                description = rs.getString("description"),
                                materialCount = rs.getInt("material_count"),
                                createdAt = rs.getLong("created_at"),
                                updatedAt = rs.getLong("updated_at"),
                            )
                        )
                    }
                }
            }
        }
        result
    }

    override suspend fun createOrGet(name: String, description: String): MaterialCategory {
        val nowSec = System.currentTimeMillis() / 1000L
        val newId = UUID.randomUUID().toString()
        return withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "INSERT OR IGNORE INTO material_category (id, name, description, created_at, updated_at) VALUES (?, ?, ?, ?, ?)"
                ).use { ps ->
                    ps.setString(1, newId)
                    ps.setString(2, name.trim())
                    ps.setString(3, description)
                    ps.setLong(4, nowSec)
                    ps.setLong(5, nowSec)
                    ps.executeUpdate()
                }
                conn.prepareStatement(
                    "SELECT id, name, description, created_at, updated_at FROM material_category WHERE name = ?"
                ).use { ps ->
                    ps.setString(1, name.trim())
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            MaterialCategory(
                                id = rs.getString("id"),
                                name = rs.getString("name"),
                                description = rs.getString("description"),
                                materialCount = 0,
                                createdAt = rs.getLong("created_at"),
                                updatedAt = rs.getLong("updated_at"),
                            )
                        } else {
                            throw IllegalStateException("Failed to create or find category: $name")
                        }
                    }
                }
            }
        }
    }

    override suspend fun deleteById(id: String): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("DELETE FROM material_category_rel WHERE category_id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM material_category WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun linkMaterials(
        materialIds: List<String>,
        categoryIds: List<String>,
    ): Unit = withContext(Dispatchers.IO) {
        if (materialIds.isEmpty() || categoryIds.isEmpty()) return@withContext
        db.useConnection { conn ->
            conn.prepareStatement(
                "INSERT OR IGNORE INTO material_category_rel (material_id, category_id) VALUES (?, ?)"
            ).use { ps ->
                for (mid in materialIds) {
                    for (cid in categoryIds) {
                        ps.setString(1, mid)
                        ps.setString(2, cid)
                        ps.executeUpdate()
                    }
                }
            }
        }
    }

    override suspend fun setMaterialCategories(
        materialId: String,
        categoryIds: List<String>,
    ): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                "DELETE FROM material_category_rel WHERE material_id = ?"
            ).use { ps ->
                ps.setString(1, materialId)
                ps.executeUpdate()
            }
            if (categoryIds.isNotEmpty()) {
                conn.prepareStatement(
                    "INSERT OR IGNORE INTO material_category_rel (material_id, category_id) VALUES (?, ?)"
                ).use { ps ->
                    for (cid in categoryIds) {
                        ps.setString(1, materialId)
                        ps.setString(2, cid)
                        ps.executeUpdate()
                    }
                }
            }
        }
    }
}
