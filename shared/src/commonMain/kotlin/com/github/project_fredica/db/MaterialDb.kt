package com.github.project_fredica.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database

/**
 * Handles the [material] base table and creates the stub detail tables for each material type.
 *
 * Detail-table ownership (each type has its own Db class):
 *  - material_video   → [MaterialVideoDb]
 *  - material_audio   → (future) MaterialAudioDb
 *  - material_image   → (future) MaterialImageDb
 *  - material_article → (future) MaterialArticleDb
 *
 * [initialize] must be called before any type-specific Db initialiser.
 */
class MaterialDb(private val db: Database) : MaterialRepo {

    /**
     * Creates:
     *  - [material]          — base table shared by all types
     *  - [material_audio]    — audio detail stub
     *  - [material_image]    — image detail stub
     *  - [material_article]  — article / paper detail stub
     */
    suspend fun initialize() {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                withContext(Dispatchers.IO) {
                    // ── Base table ─────────────────────────────────────────────────
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS material (
                            id              TEXT PRIMARY KEY,
                            type            TEXT NOT NULL,
                            title           TEXT NOT NULL DEFAULT '',
                            source_type     TEXT NOT NULL DEFAULT '',
                            source_id       TEXT NOT NULL DEFAULT '',
                            cover_url       TEXT NOT NULL DEFAULT '',
                            description     TEXT NOT NULL DEFAULT '',
                            pipeline_status TEXT NOT NULL DEFAULT 'pending',
                            extra           TEXT NOT NULL DEFAULT '{}',
                            created_at      INTEGER NOT NULL,
                            updated_at      INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )

                    // ── Audio detail (type = 'audio') ──────────────────────────────
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS material_audio (
                            material_id      TEXT PRIMARY KEY,
                            duration         INTEGER NOT NULL DEFAULT 0,
                            local_audio_path TEXT NOT NULL DEFAULT '',
                            transcript_path  TEXT NOT NULL DEFAULT ''
                        )
                        """.trimIndent()
                    )

                    // ── Image detail (type = 'image') ──────────────────────────────
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS material_image (
                            material_id      TEXT PRIMARY KEY,
                            local_image_path TEXT NOT NULL DEFAULT '',
                            width            INTEGER NOT NULL DEFAULT 0,
                            height           INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent()
                    )

                    // ── Article / Paper detail (type = 'article' | 'paper') ────────
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS material_article (
                            material_id    TEXT PRIMARY KEY,
                            word_count     INTEGER NOT NULL DEFAULT 0,
                            local_doc_path TEXT NOT NULL DEFAULT '',
                            source_url     TEXT NOT NULL DEFAULT ''
                        )
                        """.trimIndent()
                    )
                }
            }
        }
    }

    /** Returns all base material records with their category IDs. */
    override suspend fun listAll(): List<Material> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Material>()
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                SELECT m.id, m.type, m.title, m.source_type, m.source_id, m.cover_url,
                       m.description, m.pipeline_status, m.extra, m.created_at, m.updated_at,
                       GROUP_CONCAT(mcr.category_id) AS cat_ids
                FROM material m
                LEFT JOIN material_category_rel mcr ON m.id = mcr.material_id
                GROUP BY m.id
                ORDER BY m.updated_at DESC
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val catIds = rs.getString("cat_ids")
                            ?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
                        result.add(
                            Material(
                                id = rs.getString("id"),
                                type = rs.getString("type"),
                                title = rs.getString("title"),
                                sourceType = rs.getString("source_type"),
                                sourceId = rs.getString("source_id"),
                                coverUrl = rs.getString("cover_url"),
                                description = rs.getString("description"),
                                pipelineStatus = rs.getString("pipeline_status"),
                                extra = rs.getString("extra"),
                                createdAt = rs.getLong("created_at"),
                                updatedAt = rs.getLong("updated_at"),
                                categoryIds = catIds,
                            )
                        )
                    }
                }
            }
        }
        result
    }

    /**
     * Deletes base material records and manually cascades to:
     *  - All type-specific detail tables
     *  - Category associations ([material_category_rel])
     *  - Processing tasks ([material_task])
     *
     * (SQLite foreign-key enforcement is off by default, so we cascade manually.)
     */
    override suspend fun deleteByIds(ids: List<String>): Unit = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        db.useConnection { conn ->
            val placeholders = ids.joinToString(",") { "?" }

            fun del(sql: String) = conn.prepareStatement(sql).use { ps ->
                ids.forEachIndexed { i, id -> ps.setString(i + 1, id) }
                ps.executeUpdate()
            }

            // Detail tables
            del("DELETE FROM material_video   WHERE material_id IN ($placeholders)")
            del("DELETE FROM material_audio   WHERE material_id IN ($placeholders)")
            del("DELETE FROM material_image   WHERE material_id IN ($placeholders)")
            del("DELETE FROM material_article WHERE material_id IN ($placeholders)")
            // Associations
            del("DELETE FROM material_category_rel WHERE material_id IN ($placeholders)")
            del("DELETE FROM material_task          WHERE material_id IN ($placeholders)")
            // Base record last
            del("DELETE FROM material WHERE id IN ($placeholders)")
        }
    }
}
