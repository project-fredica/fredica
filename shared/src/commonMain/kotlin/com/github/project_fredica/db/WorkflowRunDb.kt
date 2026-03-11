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
            var total        = 0
            var done         = 0
            var hasFailed    = false
            var hasRunning   = false
            var hasPending   = false
            var hasCancelled = false

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
                            "completed"           -> done       += cnt
                            "failed"              -> hasFailed   = true
                            "running", "claimed"  -> hasRunning  = true
                            "pending"             -> hasPending  = true
                            "cancelled"           -> hasCancelled = true
                            // cancelled 纳入 total 计数，但不计入 done，
                            // 且需要在状态优先级中正确处理（见下方 when 表达式）
                        }
                    }
                }
            }

            // 状态优先级（高 → 低）：
            //   failed    > completed（全完成）> running/pending（进行中）> cancelled（全取消）> pending（空）
            //
            // 修复说明：原 else 分支会将"所有任务均已取消"的情况错误地回退为 "pending"，
            // 导致 WorkflowRun 永远不会进入终态。加入 hasCancelled 分支修正此问题。
            val newStatus = when {
                hasFailed                  -> "failed"
                total > 0 && done == total -> "completed"
                hasRunning || hasPending   -> "running"
                hasCancelled               -> "cancelled"
                else                       -> "pending"   // total=0 或全部取消且无其他状态
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

    // ── reconcileNonTerminal：批量对账非终态 WorkflowRun ─────────────────────

    /**
     * 对所有非终态 WorkflowRun 执行对账重算，修正因 recalculate() 调用失败而
     * 落后的汇总状态。
     *
     * 执行流程：
     * 1. 查询全部 status NOT IN (completed/failed/cancelled) 的 WF ID 及当前状态
     * 2. 对每个 WF：
     *    a. 调用 recalculate() 根据 Task 实际状态重新推导 WF 状态
     *    b. 若 total_tasks = 0（所有 Task 被删除）→ 额外修正为 failed
     *       （recalculate 在 total=0 时会设为 pending，不适合孤立工作流场景）
     * 3. 累计状态发生变化的 WF 数量
     *
     * ⚠️ total=0 的特殊情况（Kotlin 空集合逻辑陷阱的 WorkflowRun 版本）：
     *   recalculate() 中：`hasFailed=false, done(0)==total(0)` 因 total>0 守护不成立，
     *   会走 else 分支 → pending。但若一个非新建的 WF 已无任务，代表数据异常，
     *   应标记 failed 而非 pending，否则前端永远看到"进行中"状态。
     */
    override suspend fun reconcileNonTerminal(): Int = withContext(Dispatchers.IO) {
        // 1. 快照所有非终态 WF 的当前状态（id → statusBefore）
        val snapshots = mutableListOf<Pair<String, String>>()  // (id, statusBefore)
        db.useConnection { conn ->
            conn.prepareStatement(
                "SELECT id, status FROM workflow_run WHERE status NOT IN ('completed', 'failed', 'cancelled')"
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        snapshots.add(rs.getString("id") to rs.getString("status"))
                    }
                }
            }
        }

        if (snapshots.isEmpty()) return@withContext 0

        val nowSec = System.currentTimeMillis() / 1000L
        var modifiedCount = 0

        for ((wfId, statusBefore) in snapshots) {
            // 2a. 调用 recalculate() 重新从 Task 状态推导
            recalculate(wfId)

            // 2b. 若 total_tasks=0（全部 Task 被删除），recalculate 会把状态设成 pending。
            //     孤立工作流无任何 Task 可以推进，应修正为 failed。
            val wfAfter = getById(wfId) ?: continue
            if (wfAfter.totalTasks == 0) {
                db.useConnection { conn ->
                    conn.prepareStatement(
                        "UPDATE workflow_run SET status='failed', completed_at=COALESCE(completed_at,?) WHERE id=?"
                    ).use { ps ->
                        ps.setLong(1, nowSec)
                        ps.setString(2, wfId)
                        ps.executeUpdate()
                    }
                }
                modifiedCount++
                continue
            }

            // 3. 若状态发生变化，计入修改计数
            if (wfAfter.status != statusBefore) modifiedCount++
        }

        modifiedCount
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
