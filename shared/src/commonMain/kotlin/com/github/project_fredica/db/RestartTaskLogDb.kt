package com.github.project_fredica.db

// =============================================================================
// RestartTaskLogDb —— restart_task_log 表的 JDBC 实现
// =============================================================================

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database
import java.util.UUID

class RestartTaskLogDb(private val db: Database) : RestartTaskLogRepo {

    // ── 建表 ──────────────────────────────────────────────────────────────────

    override suspend fun initialize(): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS restart_task_log (
                        id                  TEXT PRIMARY KEY,
                        session_id          TEXT NOT NULL,
                        task_id             TEXT NOT NULL,
                        task_type           TEXT NOT NULL,
                        workflow_run_id     TEXT NOT NULL,
                        material_id         TEXT NOT NULL,
                        status_at_restart   TEXT NOT NULL,
                        payload             TEXT NOT NULL DEFAULT '{}',
                        progress            INTEGER NOT NULL DEFAULT 0,
                        disposition         TEXT NOT NULL DEFAULT 'pending_review',
                        new_workflow_run_id TEXT,
                        created_at          INTEGER NOT NULL,
                        resolved_at         INTEGER
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_rtl_session     ON restart_task_log(session_id)"
                )
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_rtl_workflow    ON restart_task_log(workflow_run_id)"
                )
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_rtl_disposition ON restart_task_log(disposition)"
                )
            }
        }
    }

    // ── recordRestartSession：写入重启快照 ────────────────────────────────────

    override suspend fun recordRestartSession(
        sessionId: String,
        tasks: List<Task>,
        nowSec: Long,
    ): Unit = withContext(Dispatchers.IO) {
        if (tasks.isEmpty()) return@withContext
        db.useConnection { conn ->
            // 1. 将所有已存在的 pending_review 条目批量更新为 superseded（防止重启积累）
            conn.prepareStatement(
                """
                UPDATE restart_task_log
                SET disposition='superseded', resolved_at=?
                WHERE disposition='pending_review'
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, nowSec)
                ps.executeUpdate()
            }

            // 2. 批量 INSERT 新记录
            conn.prepareStatement(
                """
                INSERT INTO restart_task_log (
                    id, session_id, task_id, task_type, workflow_run_id,
                    material_id, status_at_restart, payload, progress,
                    disposition, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending_review', ?)
                """.trimIndent()
            ).use { ps ->
                for (task in tasks) {
                    ps.setString(1, UUID.randomUUID().toString())
                    ps.setString(2, sessionId)
                    ps.setString(3, task.id)
                    ps.setString(4, task.type)
                    ps.setString(5, task.workflowRunId)
                    ps.setString(6, task.materialId)
                    ps.setString(7, task.status)
                    ps.setString(8, task.payload)
                    ps.setInt(9, task.progress)
                    ps.setLong(10, nowSec)
                    ps.executeUpdate()
                }
            }
        }
    }

    // ── listAll：查询 ─────────────────────────────────────────────────────────

    override suspend fun listAll(
        disposition: String?,
        materialId: String?,
    ): List<RestartTaskLog> = withContext(Dispatchers.IO) {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        if (disposition != null) { conditions.add("disposition = ?"); params.add(disposition) }
        if (materialId  != null) { conditions.add("material_id = ?"); params.add(materialId) }

        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        val sql = "SELECT * FROM restart_task_log $where ORDER BY created_at DESC"

        val result = mutableListOf<RestartTaskLog>()
        db.useConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                params.forEachIndexed { i, v -> ps.setString(i + 1, v.toString()) }
                ps.executeQuery().use { rs ->
                    while (rs.next()) result.add(rowToLog(rs))
                }
            }
        }
        result
    }

    // ── countPendingReview：计数 ───────────────────────────────────────────────

    override suspend fun countPendingReview(): Int = withContext(Dispatchers.IO) {
        var count = 0
        db.useConnection { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM restart_task_log WHERE disposition='pending_review'"
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    if (rs.next()) count = rs.getInt(1)
                }
            }
        }
        count
    }

    // ── updateDisposition：更新处置方式 ────────────────────────────────────────

    override suspend fun updateDisposition(
        ids: List<String>?,
        sessionId: String?,
        disposition: String,
        newWorkflowRunId: String?,
    ): Unit = withContext(Dispatchers.IO) {
        if (ids.isNullOrEmpty() && sessionId == null) return@withContext

        val nowSec = System.currentTimeMillis() / 1000L
        val finalDisposition = if (newWorkflowRunId != null) "recreated" else disposition

        db.useConnection { conn ->
            if (!ids.isNullOrEmpty()) {
                // 按 ids 逐条更新（SQLite 不支持大型 IN 列表的 PreparedStatement 绑定，逐条最安全）
                conn.prepareStatement(
                    """
                    UPDATE restart_task_log
                    SET disposition=?, new_workflow_run_id=?, resolved_at=?
                    WHERE id=?
                    """.trimIndent()
                ).use { ps ->
                    for (id in ids) {
                        ps.setString(1, finalDisposition)
                        if (newWorkflowRunId != null) ps.setString(2, newWorkflowRunId) else ps.setNull(2, java.sql.Types.VARCHAR)
                        ps.setLong(3, nowSec)
                        ps.setString(4, id)
                        ps.executeUpdate()
                    }
                }
            } else if (sessionId != null) {
                conn.prepareStatement(
                    """
                    UPDATE restart_task_log
                    SET disposition=?, new_workflow_run_id=?, resolved_at=?
                    WHERE session_id=?
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, finalDisposition)
                    if (newWorkflowRunId != null) ps.setString(2, newWorkflowRunId) else ps.setNull(2, java.sql.Types.VARCHAR)
                    ps.setLong(3, nowSec)
                    ps.setString(4, sessionId)
                    ps.executeUpdate()
                }
            }
        }
    }

    // ── rowToLog：ResultSet → RestartTaskLog ──────────────────────────────────

    private fun rowToLog(rs: java.sql.ResultSet): RestartTaskLog = RestartTaskLog(
        id                = rs.getString("id"),
        sessionId         = rs.getString("session_id"),
        taskId            = rs.getString("task_id"),
        taskType          = rs.getString("task_type"),
        workflowRunId     = rs.getString("workflow_run_id"),
        materialId        = rs.getString("material_id"),
        statusAtRestart   = rs.getString("status_at_restart"),
        payload           = rs.getString("payload"),
        progress          = rs.getInt("progress"),
        disposition       = rs.getString("disposition"),
        newWorkflowRunId  = rs.getString("new_workflow_run_id"),
        createdAt         = rs.getLong("created_at"),
        resolvedAt        = rs.getLong("resolved_at").takeIf { !rs.wasNull() },
    )
}
