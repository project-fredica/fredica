package com.github.project_fredica.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database
import java.util.UUID

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
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS material_video_category (
                            video_id    TEXT NOT NULL,
                            category_id TEXT NOT NULL,
                            PRIMARY KEY (video_id, category_id)
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
                       COUNT(vc.video_id) AS video_count
                FROM material_category c
                LEFT JOIN material_video_category vc ON c.id = vc.category_id
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
                                videoCount = rs.getInt("video_count"),
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
                // Always SELECT after INSERT OR IGNORE to get the actual row (may be pre-existing)
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
                                videoCount = 0,
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
            conn.prepareStatement("DELETE FROM material_video_category WHERE category_id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM material_category WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun linkVideos(videoIds: List<String>, categoryIds: List<String>): Unit =
        withContext(Dispatchers.IO) {
            if (videoIds.isEmpty() || categoryIds.isEmpty()) return@withContext
            db.useConnection { conn ->
                conn.prepareStatement(
                    "INSERT OR IGNORE INTO material_video_category (video_id, category_id) VALUES (?, ?)"
                ).use { ps ->
                    for (vid in videoIds) {
                        for (cid in categoryIds) {
                            ps.setString(1, vid)
                            ps.setString(2, cid)
                            ps.executeUpdate()
                        }
                    }
                }
            }
        }

    override suspend fun setVideoCategories(videoId: String, categoryIds: List<String>): Unit =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement("DELETE FROM material_video_category WHERE video_id = ?").use { ps ->
                    ps.setString(1, videoId)
                    ps.executeUpdate()
                }
                if (categoryIds.isNotEmpty()) {
                    conn.prepareStatement(
                        "INSERT OR IGNORE INTO material_video_category (video_id, category_id) VALUES (?, ?)"
                    ).use { ps ->
                        for (cid in categoryIds) {
                            ps.setString(1, videoId)
                            ps.setString(2, cid)
                            ps.executeUpdate()
                        }
                    }
                }
            }
        }
}
