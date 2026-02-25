package com.github.project_fredica.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database

class MaterialTaskDb(private val db: Database) : MaterialTaskRepo {

    suspend fun initialize() {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                withContext(Dispatchers.IO) {
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS material_task (
                            id          TEXT PRIMARY KEY,
                            material_id TEXT NOT NULL,
                            task_type   TEXT NOT NULL,
                            status      TEXT NOT NULL DEFAULT 'queued',
                            depends_on  TEXT NOT NULL DEFAULT '[]',
                            input_path  TEXT NOT NULL DEFAULT '',
                            output_path TEXT NOT NULL DEFAULT '',
                            error_msg   TEXT NOT NULL DEFAULT '',
                            created_at  INTEGER NOT NULL,
                            updated_at  INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                }
            }
        }
    }

    override suspend fun create(task: MaterialTask) = createAll(listOf(task))

    override suspend fun createAll(tasks: List<MaterialTask>): Unit = withContext(Dispatchers.IO) {
        if (tasks.isEmpty()) return@withContext
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO material_task (
                    id, material_id, task_type, status, depends_on,
                    input_path, output_path, error_msg, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO NOTHING
                """.trimIndent()
            ).use { ps ->
                for (t in tasks) {
                    ps.setString(1, t.id)
                    ps.setString(2, t.materialId)
                    ps.setString(3, t.taskType)
                    ps.setString(4, t.status)
                    ps.setString(5, t.dependsOn)
                    ps.setString(6, t.inputPath)
                    ps.setString(7, t.outputPath)
                    ps.setString(8, t.errorMsg)
                    ps.setLong(9, t.createdAt)
                    ps.setLong(10, t.updatedAt)
                    ps.executeUpdate()
                }
            }
        }
    }

    override suspend fun listByMaterialId(materialId: String): List<MaterialTask> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<MaterialTask>()
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM material_task WHERE material_id = ? ORDER BY created_at ASC"
                ).use { ps ->
                    ps.setString(1, materialId)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            result.add(
                                MaterialTask(
                                    id = rs.getString("id"),
                                    materialId = rs.getString("material_id"),
                                    taskType = rs.getString("task_type"),
                                    status = rs.getString("status"),
                                    dependsOn = rs.getString("depends_on"),
                                    inputPath = rs.getString("input_path"),
                                    outputPath = rs.getString("output_path"),
                                    errorMsg = rs.getString("error_msg"),
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

    override suspend fun updateStatus(
        id: String,
        status: String,
        errorMsg: String,
        outputPath: String,
    ): Unit = withContext(Dispatchers.IO) {
        val nowSec = System.currentTimeMillis() / 1000L
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                UPDATE material_task
                SET status = ?, error_msg = ?, output_path = ?, updated_at = ?
                WHERE id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, status)
                ps.setString(2, errorMsg)
                ps.setString(3, outputPath)
                ps.setLong(4, nowSec)
                ps.setString(5, id)
                ps.executeUpdate()
            }
        }
    }
}
