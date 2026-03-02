package com.github.project_fredica.db

// =============================================================================
// PipelineDb —— pipeline_instance 和 task_event 表的 JDBC 实现
// =============================================================================
//
// 职责划分：
//   - PipelineDb 负责管理 pipeline_instance 和 task_event 两张表
//   - recalculate() 需要读 task 表来统计子任务状态，再写 pipeline_instance，
//     因此也在此类实现（而不是在 TaskDb），避免跨方向的表依赖。
//
// cancel() 逻辑：
//   - 只取消 status='pending' 的任务（还未被认领的任务）
//   - 已经 running/claimed 的任务不打断，让它自然完成或超时
//   - 取消后将 pipeline_instance.status 改为 'cancelled'
// =============================================================================

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database

class PipelineDb(private val db: Database) : PipelineRepo {

    // ── 建表 ──────────────────────────────────────────────────────────────────

    /**
     * 创建 pipeline_instance 和 task_event 两张表（如果不存在）。
     * 在 [FredicaApi.jvm.kt] 初始化时调用一次即可。
     */
    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.createStatement().use { stmt ->
                    // 流水线实例表：记录一次流水线的整体状态和进度
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS pipeline_instance (
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
                    // 任务事件表：审计日志，记录每次状态变更事件（预留，Phase 3+ 使用）
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS task_event (
                            id          TEXT PRIMARY KEY,
                            task_id     TEXT NOT NULL,
                            node_id     TEXT NOT NULL,
                            event_type  TEXT NOT NULL,
                            message     TEXT,
                            occurred_at INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                }
            }
        }
    }

    // ── create：插入流水线 ────────────────────────────────────────────────────

    /**
     * 插入一条流水线记录。
     * 用 ON CONFLICT(id) DO NOTHING 防止重复插入（id 应由调用方保证唯一）。
     */
    override suspend fun create(pipeline: PipelineInstance): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO pipeline_instance (id, material_id, template, status, total_tasks, done_tasks, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO NOTHING
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, pipeline.id)
                ps.setString(2, pipeline.materialId)
                ps.setString(3, pipeline.template)
                ps.setString(4, pipeline.status)
                ps.setInt(5,    pipeline.totalTasks)
                ps.setInt(6,    pipeline.doneTasks)
                ps.setLong(7,   pipeline.createdAt)
                ps.executeUpdate()
            }
        }
    }

    // ── getById / listAll：查询 ────────────────────────────────────────────────

    /** 按 id 查询单条流水线，不存在时返回 null。 */
    override suspend fun getById(id: String): PipelineInstance? = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("SELECT * FROM pipeline_instance WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rowToPipeline(rs) else null
                }
            }
        }
    }

    /**
     * 分页查询流水线，支持按 status / template 过滤，固定按 created_at DESC 排序。
     *
     * pageSize 限制在 1‒100 之间；page 超出范围时自动回到最近合法值。
     * total = 0 时 totalPages 返回 1（避免前端显示"第 0 页"）。
     */
    override suspend fun listPaged(query: PipelineListQuery): PipelinePage = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            // ── 构造 WHERE 子句 ─────────────────────────────────────────────
            val conditions  = mutableListOf<String>()
            val bindParams  = mutableListOf<String>()
            query.status?.let   { conditions += "status = ?";   bindParams += it }
            query.template?.let { conditions += "template = ?"; bindParams += it }
            val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"

            val safePageSize = query.pageSize.coerceIn(1, 100)

            // ── COUNT ───────────────────────────────────────────────────────
            val total = conn.prepareStatement(
                "SELECT COUNT(*) FROM pipeline_instance $where"
            ).use { ps ->
                bindParams.forEachIndexed { i, p -> ps.setString(i + 1, p) }
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }

            val totalPages = if (total == 0) 1 else (total + safePageSize - 1) / safePageSize
            val safePage   = query.page.coerceIn(1, totalPages)
            val offset     = (safePage - 1) * safePageSize

            // ── 数据行 ──────────────────────────────────────────────────────
            val items = mutableListOf<PipelineInstance>()
            conn.prepareStatement(
                "SELECT * FROM pipeline_instance $where ORDER BY created_at DESC LIMIT ? OFFSET ?"
            ).use { ps ->
                var idx = 1
                bindParams.forEach { ps.setString(idx++, it) }
                ps.setInt(idx++, safePageSize)
                ps.setInt(idx,   offset)
                ps.executeQuery().use { rs ->
                    while (rs.next()) items.add(rowToPipeline(rs))
                }
            }

            PipelinePage(
                items      = items,
                total      = total,
                page       = safePage,
                pageSize   = safePageSize,
                totalPages = totalPages,
            )
        }
    }

    // ── cancel：取消流水线 ────────────────────────────────────────────────────

    /**
     * 取消流水线：将所有 pending 状态的任务改为 cancelled，并将流水线本身设为 cancelled。
     *
     * 注意：running / claimed 状态的任务不受影响——它们会继续执行直到完成。
     * 这样设计是因为强行停止正在运行的任务可能导致文件损坏或资源泄漏。
     *
     * @return 被取消的任务数量
     */
    override suspend fun cancel(id: String): Int = withContext(Dispatchers.IO) {
        var count = 0
        db.useConnection { conn ->
            // 步骤 1：取消所有还未被认领的任务
            conn.prepareStatement(
                "UPDATE task SET status='cancelled' WHERE pipeline_id=? AND status='pending'"
            ).use { ps ->
                ps.setString(1, id)
                count = ps.executeUpdate()
            }
            // 步骤 2：将流水线本身标记为 cancelled（已完成/失败的流水线不受影响）
            conn.prepareStatement(
                "UPDATE pipeline_instance SET status='cancelled' WHERE id=? AND status NOT IN ('completed','failed')"
            ).use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
        }
        count
    }

    // ── recalculate：重新统计流水线进度 ──────────────────────────────────────

    /**
     * 根据子任务的当前状态，重新计算并更新 pipeline_instance 的 done_tasks、
     * total_tasks 和 status 字段。每次任务状态变更后由 WorkerEngine 触发。
     *
     * 状态判定规则（优先级从高到低）：
     * 1. 有任何 failed 任务           → pipeline = failed
     * 2. 所有任务都 completed         → pipeline = completed（同时记录 completed_at）
     * 3. 有任务 running 或 pending    → pipeline = running
     * 4. 其他（全部 cancelled 等）    → pipeline = pending
     */
    override suspend fun recalculate(pipelineId: String): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            // 按 status 分组统计任务数量
            var total      = 0
            var done       = 0
            var hasFailed  = false
            var hasRunning = false
            var hasPending = false

            conn.prepareStatement(
                "SELECT status, COUNT(*) as cnt FROM task WHERE pipeline_id=? GROUP BY status"
            ).use { ps ->
                ps.setString(1, pipelineId)
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
                            // "cancelled" 不影响 pipeline 状态判定
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
            // 只有首次进入 completed 时才写 completed_at（COALESCE 保留已有值）
            val completedAt = if (newStatus == "completed") nowSec else null

            conn.prepareStatement(
                """
                UPDATE pipeline_instance
                SET done_tasks=?, total_tasks=?, status=?,
                    completed_at=COALESCE(completed_at, ?)
                WHERE id=?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1,    done)
                ps.setInt(2,    total)
                ps.setString(3, newStatus)
                if (completedAt != null) ps.setLong(4, completedAt) else ps.setNull(4, java.sql.Types.INTEGER)
                ps.setString(5, pipelineId)
                ps.executeUpdate()
            }
        }
    }

    // ── addEvent：写入审计事件 ────────────────────────────────────────────────

    /**
     * 记录一条任务事件（审计日志）。
     * Phase 1 暂不主动调用，接口预留给 Phase 3 的多节点同步使用。
     */
    override suspend fun addEvent(event: TaskEvent): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO task_event (id, task_id, node_id, event_type, message, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, event.id)
                ps.setString(2, event.taskId)
                ps.setString(3, event.nodeId)
                ps.setString(4, event.eventType)
                ps.setString(5, event.message)
                ps.setLong(6,   event.occurredAt)
                ps.executeUpdate()
            }
        }
    }

    // ── hasActivePipeline：幂等检查 ───────────────────────────────────────────

    /**
     * 检查指定（素材 ID + 模板）是否已存在活跃流水线。
     *
     * 活跃 = 非终态（不是 completed / failed / cancelled）。
     * PipelineCreateRoute 在创建前调用此方法，防止重复提交。
     */
    override suspend fun hasActivePipeline(materialId: String, template: String): Boolean =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    """
                    SELECT 1 FROM pipeline_instance
                    WHERE material_id=? AND template=?
                      AND status NOT IN ('completed','failed','cancelled')
                    LIMIT 1
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, materialId)
                    ps.setString(2, template)
                    ps.executeQuery().use { rs -> rs.next() }
                }
            }
        }

    // ── rowToPipeline：ResultSet → PipelineInstance ───────────────────────────

    private fun rowToPipeline(rs: java.sql.ResultSet): PipelineInstance = PipelineInstance(
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
