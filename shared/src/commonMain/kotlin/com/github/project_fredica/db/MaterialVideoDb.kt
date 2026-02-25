package com.github.project_fredica.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database

/**
 * DB implementation for video materials.
 *
 * Storage layout:
 *  - [material]       — base fields (shared with all material types)
 *  - [material_video] — video-specific detail (1:1 with material WHERE type = 'video')
 *
 * Prerequisite: call [MaterialDb.initialize] before calling [initialize] here,
 * as [material_video] is logically a child of [material].
 */
class MaterialVideoDb(private val db: Database) : MaterialVideoRepo {

    /** Creates the [material_video] detail table. */
    suspend fun initialize() {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                withContext(Dispatchers.IO) {
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS material_video (
                            material_id      TEXT PRIMARY KEY,
                            duration         INTEGER NOT NULL DEFAULT 0,
                            local_video_path TEXT NOT NULL DEFAULT '',
                            local_audio_path TEXT NOT NULL DEFAULT '',
                            transcript_path  TEXT NOT NULL DEFAULT ''
                        )
                        """.trimIndent()
                    )
                }
            }
        }
    }

    /**
     * Inserts or updates video materials in both [material] and [material_video].
     * Returns the count of genuinely new inserts (IDs not previously present).
     */
    override suspend fun upsertAll(videos: List<MaterialVideo>): Int = withContext(Dispatchers.IO) {
        if (videos.isEmpty()) return@withContext 0
        var newCount = 0

        db.useConnection { conn ->
            // Determine which IDs are truly new
            val ids = videos.map { it.id }
            val placeholders = ids.joinToString(",") { "?" }
            val existingIds = mutableSetOf<String>()
            conn.prepareStatement("SELECT id FROM material WHERE id IN ($placeholders)").use { ps ->
                ids.forEachIndexed { i, id -> ps.setString(i + 1, id) }
                ps.executeQuery().use { rs -> while (rs.next()) existingIds.add(rs.getString(1)) }
            }
            newCount = videos.count { it.id !in existingIds }

            // ── Upsert base material record ────────────────────────────────────
            conn.prepareStatement(
                """
                INSERT INTO material (
                    id, type, title, source_type, source_id, cover_url,
                    description, pipeline_status, extra, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    title           = excluded.title,
                    source_type     = excluded.source_type,
                    source_id       = excluded.source_id,
                    cover_url       = excluded.cover_url,
                    description     = excluded.description,
                    pipeline_status = excluded.pipeline_status,
                    extra           = excluded.extra,
                    updated_at      = excluded.updated_at
                """.trimIndent()
            ).use { ps ->
                for (v in videos) {
                    ps.setString(1, v.id)
                    ps.setString(2, MaterialType.VIDEO)
                    ps.setString(3, v.title)
                    ps.setString(4, v.sourceType)
                    ps.setString(5, v.sourceId)
                    ps.setString(6, v.coverUrl)
                    ps.setString(7, v.description)
                    ps.setString(8, v.pipelineStatus)
                    ps.setString(9, v.extra)
                    ps.setLong(10, v.createdAt)
                    ps.setLong(11, v.updatedAt)
                    ps.executeUpdate()
                }
            }

            // ── Upsert video detail record ─────────────────────────────────────
            conn.prepareStatement(
                """
                INSERT INTO material_video (
                    material_id, duration, local_video_path, local_audio_path, transcript_path
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(material_id) DO UPDATE SET
                    duration         = excluded.duration,
                    local_video_path = excluded.local_video_path,
                    local_audio_path = excluded.local_audio_path,
                    transcript_path  = excluded.transcript_path
                """.trimIndent()
            ).use { ps ->
                for (v in videos) {
                    ps.setString(1, v.id)
                    ps.setInt(2, v.duration)
                    ps.setString(3, v.localVideoPath)
                    ps.setString(4, v.localAudioPath)
                    ps.setString(5, v.transcriptPath)
                    ps.executeUpdate()
                }
            }
        }

        newCount
    }

    /**
     * Returns all video materials as a JOIN view (material + material_video + category IDs).
     */
    override suspend fun listAll(): List<MaterialVideo> = withContext(Dispatchers.IO) {
        val result = mutableListOf<MaterialVideo>()
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                SELECT
                    m.id, m.type, m.title, m.source_type, m.source_id, m.cover_url,
                    m.description, m.pipeline_status, m.extra, m.created_at, m.updated_at,
                    mv.duration, mv.local_video_path, mv.local_audio_path, mv.transcript_path,
                    GROUP_CONCAT(mcr.category_id) AS cat_ids
                FROM material m
                JOIN material_video mv ON m.id = mv.material_id
                LEFT JOIN material_category_rel mcr ON m.id = mcr.material_id
                WHERE m.type = 'video'
                GROUP BY m.id
                ORDER BY m.updated_at DESC
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val catIds = rs.getString("cat_ids")
                            ?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
                        result.add(
                            MaterialVideo(
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
                                duration = rs.getInt("duration"),
                                localVideoPath = rs.getString("local_video_path"),
                                localAudioPath = rs.getString("local_audio_path"),
                                transcriptPath = rs.getString("transcript_path"),
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
     * Deletes video materials and manually cascades to:
     *  - [material_video] detail records
     *  - [material_category_rel] category associations
     *  - [material_task] processing tasks
     *  - [material] base records (last, after all children are removed)
     */
    override suspend fun deleteByIds(ids: List<String>): Unit = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        db.useConnection { conn ->
            val placeholders = ids.joinToString(",") { "?" }

            fun del(sql: String) = conn.prepareStatement(sql).use { ps ->
                ids.forEachIndexed { i, id -> ps.setString(i + 1, id) }
                ps.executeUpdate()
            }

            del("DELETE FROM material_video         WHERE material_id IN ($placeholders)")
            del("DELETE FROM material_category_rel  WHERE material_id IN ($placeholders)")
            del("DELETE FROM material_task          WHERE material_id IN ($placeholders)")
            del("DELETE FROM material               WHERE id          IN ($placeholders)")
        }
    }
}
