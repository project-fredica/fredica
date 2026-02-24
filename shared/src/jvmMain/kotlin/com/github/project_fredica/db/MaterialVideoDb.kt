package com.github.project_fredica.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database
import org.ktorm.dsl.*
import org.ktorm.schema.BaseTable
import org.ktorm.schema.int
import org.ktorm.schema.long
import org.ktorm.schema.varchar

object MaterialVideoTable : BaseTable<Nothing>("material_video") {
    val id = varchar("id")
    val sourceType = varchar("source_type")
    val sourceId = varchar("source_id")
    val title = varchar("title")
    val coverUrl = varchar("cover_url")
    val description = varchar("description")
    val duration = int("duration")
    val pipelineStatus = varchar("pipeline_status")
    val localVideoPath = varchar("local_video_path")
    val localAudioPath = varchar("local_audio_path")
    val transcriptPath = varchar("transcript_path")
    val extra = varchar("extra")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override fun doCreateEntity(row: QueryRowSet, withReferences: Boolean): Nothing =
        throw UnsupportedOperationException()
}

class MaterialVideoDb(private val db: Database) : MaterialVideoRepo {

    suspend fun initialize() {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                withContext(Dispatchers.IO) {
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS material_video (
                            id               TEXT PRIMARY KEY,
                            source_type      TEXT NOT NULL,
                            source_id        TEXT NOT NULL,
                            title            TEXT NOT NULL DEFAULT '',
                            cover_url        TEXT NOT NULL DEFAULT '',
                            description      TEXT NOT NULL DEFAULT '',
                            duration         INTEGER NOT NULL DEFAULT 0,
                            pipeline_status  TEXT NOT NULL DEFAULT 'pending',
                            local_video_path TEXT NOT NULL DEFAULT '',
                            local_audio_path TEXT NOT NULL DEFAULT '',
                            transcript_path  TEXT NOT NULL DEFAULT '',
                            extra            TEXT NOT NULL DEFAULT '{}',
                            created_at       INTEGER NOT NULL,
                            updated_at       INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                }
            }
        }
    }

    override suspend fun upsertAll(videos: List<MaterialVideo>): Int = withContext(Dispatchers.IO) {
        if (videos.isEmpty()) return@withContext 0

        var newCount = 0

        db.useConnection { conn ->
            // Count truly new inserts by checking existing IDs (IDs are now deterministic)
            val ids = videos.map { it.id }
            val placeholders = ids.joinToString(",") { "?" }
            val existingIds = mutableSetOf<String>()
            conn.prepareStatement("SELECT id FROM material_video WHERE id IN ($placeholders)").use { ps ->
                ids.forEachIndexed { i, id -> ps.setString(i + 1, id) }
                ps.executeQuery().use { rs ->
                    while (rs.next()) existingIds.add(rs.getString(1))
                }
            }
            newCount = videos.count { it.id !in existingIds }

            conn.prepareStatement(
                """
                INSERT INTO material_video (
                    id, source_type, source_id, title, cover_url, description,
                    duration, pipeline_status, local_video_path, local_audio_path,
                    transcript_path, extra, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    title            = excluded.title,
                    cover_url        = excluded.cover_url,
                    description      = excluded.description,
                    duration         = excluded.duration,
                    pipeline_status  = excluded.pipeline_status,
                    local_video_path = excluded.local_video_path,
                    local_audio_path = excluded.local_audio_path,
                    transcript_path  = excluded.transcript_path,
                    extra            = excluded.extra,
                    updated_at       = excluded.updated_at
                """.trimIndent()
            ).use { ps ->
                for (v in videos) {
                    ps.setString(1, v.id)
                    ps.setString(2, v.sourceType)
                    ps.setString(3, v.sourceId)
                    ps.setString(4, v.title)
                    ps.setString(5, v.coverUrl)
                    ps.setString(6, v.description)
                    ps.setInt(7, v.duration)
                    ps.setString(8, v.pipelineStatus)
                    ps.setString(9, v.localVideoPath)
                    ps.setString(10, v.localAudioPath)
                    ps.setString(11, v.transcriptPath)
                    ps.setString(12, v.extra)
                    ps.setLong(13, v.createdAt)
                    ps.setLong(14, v.updatedAt)
                    ps.executeUpdate()
                }
            }
        }

        newCount
    }

    /**
     * Returns all videos ordered by updated_at DESC, each with their category IDs
     * (via LEFT JOIN + GROUP_CONCAT on material_video_category).
     */
    override suspend fun listAll(): List<MaterialVideo> = withContext(Dispatchers.IO) {
        val result = mutableListOf<MaterialVideo>()
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                SELECT mv.id, mv.source_type, mv.source_id, mv.title, mv.cover_url,
                       mv.description, mv.duration, mv.pipeline_status,
                       mv.local_video_path, mv.local_audio_path, mv.transcript_path,
                       mv.extra, mv.created_at, mv.updated_at,
                       GROUP_CONCAT(vc.category_id) AS cat_ids
                FROM material_video mv
                LEFT JOIN material_video_category vc ON mv.id = vc.video_id
                GROUP BY mv.id
                ORDER BY mv.updated_at DESC
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val catIdsRaw = rs.getString("cat_ids")
                        val catIds = catIdsRaw
                            ?.split(",")
                            ?.filter { it.isNotEmpty() }
                            ?: emptyList()
                        result.add(
                            MaterialVideo(
                                id = rs.getString("id"),
                                sourceType = rs.getString("source_type"),
                                sourceId = rs.getString("source_id"),
                                title = rs.getString("title"),
                                coverUrl = rs.getString("cover_url"),
                                description = rs.getString("description"),
                                duration = rs.getInt("duration"),
                                pipelineStatus = rs.getString("pipeline_status"),
                                localVideoPath = rs.getString("local_video_path"),
                                localAudioPath = rs.getString("local_audio_path"),
                                transcriptPath = rs.getString("transcript_path"),
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

    override suspend fun deleteByIds(ids: List<String>): Unit = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        db.useConnection { conn ->
            val placeholders = ids.joinToString(",") { "?" }
            // Delete junction entries first so the category video counts stay accurate
            conn.prepareStatement("DELETE FROM material_video_category WHERE video_id IN ($placeholders)").use { ps ->
                ids.forEachIndexed { index, id -> ps.setString(index + 1, id) }
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM material_video WHERE id IN ($placeholders)").use { ps ->
                ids.forEachIndexed { index, id -> ps.setString(index + 1, id) }
                ps.executeUpdate()
            }
        }
    }
}
