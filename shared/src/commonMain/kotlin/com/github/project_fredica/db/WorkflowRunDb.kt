package com.github.project_fredica.db

// =============================================================================
// WorkflowRunDb —— workflow_run 表的 JDBC 实现
// =============================================================================
//
// 职责：
//   - 管理 workflow_run 表（创建、查询、状态汇总）
//   - recalculate() 需要读 task 表来统计子任务状态，再写 workflow_run，
//     因此也在此类实现（而不是在 TaskDb），避免跨方向的表依赖。
// =============================================================================

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database

class WorkflowRunDb(private val db: Database) : WorkflowRunRepo {

    // ── 建表 ──────────────────────────────────────────────────────────────────

    /**
     * 创建 workflow_run 表（如果不存在）。
     * 在 FredicaApi.jvm.kt 初始化时调用一次即可。
     */
    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS workflow_run (
                            id            TEXT PRIMARY KEY,
                            material_id   TEXT NOT NULL,
                            template      TEXT NOT NULL,
                            status        TEXT NOT NULL DEFAULT 'pending',
                            total_tasks   INTEGER NOT NULL DEFAULT 0,
                            done_tasks    INTEGER NOT NULL DEFAULT 0,
                            created_at    INTEGER NOT NULL,
                            completed_at  INTEGER
                        )
                        """.trimIndent()
                    )
                }
            }
        }
    }

    // ── create：插入运行实例 ──────────────────────────────────────────────────

    /**
     * 插入一条运行实例记录。
     * 用 ON CONFLICT(id) DO NOTHING 防止重复插入（id 由调用方保证唯一）。
     */
    override suspend fun create(run: WorkflowRun): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO workflow_run (id, material_id, template, status, total_tasks, done_tasks, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO NOTHING
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, run.id)
                ps.setString(2, run.materialId)
                ps.setString(3, run.template)
                ps.setString(4, run.status)
                ps.setInt(5,    run.totalTasks)
                ps.setInt(6,    run.doneTasks)
                ps.setLong(7,   run.createdAt)
                ps.executeUpdate()
            }
        }
    }

    // ── getById：查询 ─────────────────────────────────────────────────────────

    /** 按 id 查询单条运行实例，不存在时返回 null。 */
    override suspend fun getById(id: String): WorkflowRun? = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("SELECT * FROM workflow_run WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rowToWorkflowRun(rs) else null
                }
            }
        }
    }

    // ── recalculate：重新统计运行实例进度 ─────────────────────────────────────

    /**
     * 根据子任务的当前状态，重新计算并更新 workflow_run 的 done_tasks、
     * total_tasks 和 status 字段。每次任务状态变更后由 WorkerEngine 触发。
     *
     * 状态判定规则（优先级从高到低）：
     * 1. 有任何 failed 任务           → workflow_run = failed
     * 2. 所有任务都 completed         → workflow_run = completed（同时记录 completed_at）
     * 3. 有任务 running 或 pending    → workflow_run = running
     * 4. 其他（全部 cancelled 等）    → workflow_run = pending
     */
    override suspend fun recalculate(workflowRunId: String): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            var total      = 0
            var done       = 0
            var hasFailed  = false
            var hasRunning = false
            var hasPending = false

            conn.prepareStatement(
                "SELECT status, COUNT(*) as cnt FROM task WHERE workflow_run_id=? GROUP BY status"
            ).use { ps ->
                ps.setString(1, workflowRunId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val s   = rs.getString("status")
                        val cnt = rs.getInt("cnt")
                        total += cnt
                        when (s) {
                            "completed"           -> done += cnt
                            "failed"              -> hasFailed  = true
                            "running", "claimed"  -> hasRunning = true
                            "pending"             -> hasPending = true
                            // "cancelled" 不影响状态判定
                        }
                    }
                }
            }

            val newStatus = when {
                hasFailed                  -> "failed"
                total > 0 && done == total -> "completed"
                hasRunning || hasPending   -> "running"
                else                       -> "pending"
            }

            val nowSec      = System.currentTimeMillis() / 1000L
            val completedAt = if (newStatus == "completed") nowSec else null

            conn.prepareStatement(
                """
                UPDATE workflow_run
                SET done_tasks=?, total_tasks=?, status=?,
                    completed_at=COALESCE(completed_at, ?)
                WHERE id=?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1,    done)
                ps.setInt(2,    total)
                ps.setString(3, newStatus)
                if (completedAt != null) ps.setLong(4, completedAt) else ps.setNull(4, java.sql.Types.INTEGER)
                ps.setString(5, workflowRunId)
                ps.executeUpdate()
            }
        }
    }

    // ── rowToWorkflowRun：ResultSet → WorkflowRun ────────────────────────────

    private fun rowToWorkflowRun(rs: java.sql.ResultSet): WorkflowRun = WorkflowRun(
        id          = rs.getString("id"),
        materialId  = rs.getString("material_id"),
        template    = rs.getString("template"),
        status      = rs.getString("status"),
        totalTasks  = rs.getInt("total_tasks"),
        doneTasks   = rs.getInt("done_tasks"),
        createdAt   = rs.getLong("created_at"),
        completedAt = rs.getLong("completed_at").takeIf { !rs.wasNull() },
    )
}
