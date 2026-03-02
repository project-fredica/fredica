package com.github.project_fredica.db

// =============================================================================
// TaskDb —— task 表的 JDBC 实现
// =============================================================================
//
// 重要设计选择：
//   1. 使用 ktorm 的 db.useConnection { } 直接执行原生 SQL，而非 ktorm 的 DSL。
//      原因：claimNext() 需要 "UPDATE ... RETURNING id" 这样的原子 SQL，
//      ktorm DSL 不支持 RETURNING 子句，因此全部用 JDBC PreparedStatement。
//
//   2. claimNext() 使用单条 SQL 完成"查找 + 标记"两步操作（原子认领）。
//      SQLite 的 UPDATE ... WHERE id=(SELECT ...) RETURNING id 在单进程内天然原子，
//      多节点时需配合 HTTP 乐观锁（Phase 3 实现，此处无需额外处理）。
//
//   3. recalculate() 不在此类实现：它要写 pipeline_instance 表，
//      职责属于 PipelineDb，不应让 TaskDb 跨表依赖。
// =============================================================================

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database

class TaskDb(private val db: Database) : TaskRepo {

    // ── 建表 ──────────────────────────────────────────────────────────────────

    /**
     * 创建 task 表（如果不存在）。
     * 在 [FredicaApi.jvm.kt] 初始化时调用一次即可。
     */
    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS task (
                            id                  TEXT PRIMARY KEY,
                            type                TEXT NOT NULL,
                            pipeline_id         TEXT NOT NULL,
                            material_id         TEXT NOT NULL,
                            status              TEXT NOT NULL DEFAULT 'pending',
                            priority            INTEGER NOT NULL DEFAULT 0,
                            depends_on          TEXT NOT NULL DEFAULT '[]',
                            cache_policy        TEXT NOT NULL DEFAULT 'NONE',
                            payload             TEXT NOT NULL DEFAULT '{}',
                            result              TEXT,
                            result_acked_at     INTEGER,
                            error               TEXT,
                            error_type          TEXT,
                            excluded_nodes      TEXT NOT NULL DEFAULT '[]',
                            idempotency_key     TEXT UNIQUE,
                            retry_count         INTEGER NOT NULL DEFAULT 0,
                            max_retries         INTEGER NOT NULL DEFAULT 3,
                            created_by          TEXT NOT NULL DEFAULT 'local',
                            claimed_by          TEXT,
                            original_claimed_by TEXT,
                            file_node_id        TEXT,
                            node_affinity       TEXT,
                            created_at          INTEGER NOT NULL,
                            claimed_at          INTEGER,
                            started_at          INTEGER,
                            completed_at        INTEGER,
                            heartbeat_at        INTEGER,
                            stale_at            INTEGER,
                            reclaimed_at        INTEGER
                        )
                        """.trimIndent()
                    )
                }
            }
        }
    }

    // ── claimNext：DAG 感知的原子认领 ─────────────────────────────────────────

    /**
     * 原子认领下一个可执行任务，并返回认领后的完整 Task 对象。
     *
     * SQL 逻辑分两步（合并在一条语句中执行）：
     *
     * **步骤 1 — 找候选任务（子查询）：**
     * ```sql
     * SELECT t.id FROM task t
     * WHERE t.status = 'pending'
     *   AND NOT EXISTS (
     *     -- 检查 depends_on 里的每个前置任务是否全部 completed
     *     SELECT 1 FROM task dep
     *     JOIN json_each(t.depends_on) je ON dep.id = je.value
     *     WHERE dep.status != 'completed'
     *   )
     * ORDER BY t.priority DESC, t.created_at ASC
     * LIMIT 1
     * ```
     * `json_each(t.depends_on)` 是 SQLite 内置的 JSON 数组展开函数，
     * 把 `["task-a","task-b"]` 展开成逐行结果，再 JOIN task 表校验状态。
     * `NOT EXISTS(未完成依赖)` 意即：只选"所有前置任务都已 completed"的任务。
     *
     * **步骤 2 — 原子标记（UPDATE + RETURNING）：**
     * ```sql
     * UPDATE task SET status='claimed', claimed_by=?, claimed_at=?
     * WHERE id = (步骤1子查询)
     * RETURNING id
     * ```
     * 查找和更新在同一条 SQL 里，SQLite 单进程内保证原子性。
     * `RETURNING id` 返回被更新的行 ID，没有行被更新则 ResultSet 为空（返回 null）。
     *
     * @param workerId  认领者 ID，写入 claimed_by 字段
     * @return          认领到的任务，队列为空或所有任务都被阻塞时返回 null
     */
    override suspend fun claimNext(workerId: String): Task? = withContext(Dispatchers.IO) {
        val nowSec = System.currentTimeMillis() / 1000L
        var claimedId: String? = null

        db.useConnection { conn ->
            conn.prepareStatement(
                """
                UPDATE task SET status='claimed', claimed_by=?, claimed_at=?
                WHERE id = (
                  SELECT t.id FROM task t
                  WHERE t.status = 'pending'
                    AND NOT EXISTS (
                      SELECT 1 FROM task dep
                      JOIN json_each(t.depends_on) je ON dep.id = je.value
                      WHERE dep.status != 'completed'
                    )
                  ORDER BY t.priority DESC, t.created_at ASC
                  LIMIT 1
                )
                RETURNING id
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, workerId)
                ps.setLong(2, nowSec)
                val rs = ps.executeQuery()
                if (rs.next()) {
                    claimedId = rs.getString("id")
                }
            }
        }

        // 认领成功后再查完整行（RETURNING 只返回 id，不返回全行）
        claimedId?.let { id ->
            db.useConnection { conn ->
                conn.prepareStatement("SELECT * FROM task WHERE id = ?").use { ps ->
                    ps.setString(1, id)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rowToTask(rs) else null
                    }
                }
            }
        }
    }

    // ── updateStatus：更新任务状态 ────────────────────────────────────────────

    /**
     * 更新任务状态，同时记录时间戳和执行结果。
     *
     * - status → running：自动填写 started_at（用 COALESCE 保留已有值，防止重复覆盖）
     * - status → completed/failed/cancelled：自动填写 completed_at
     * - result/error/error_type：由 Executor 传入，null 表示不覆盖
     */
    override suspend fun updateStatus(
        id: String,
        status: String,
        result: String?,
        error: String?,
        errorType: String?,
    ): Unit = withContext(Dispatchers.IO) {
        val nowSec = System.currentTimeMillis() / 1000L
        // 只有终态才写 completed_at；COALESCE 保证不会覆盖已有的时间戳
        val completedAt = if (status in setOf("completed", "failed", "cancelled")) nowSec else null
        val startedAt   = if (status == "running") nowSec else null

        db.useConnection { conn ->
            conn.prepareStatement(
                """
                UPDATE task
                SET status=?, result=?, error=?, error_type=?,
                    completed_at=COALESCE(completed_at, ?),
                    started_at=COALESCE(started_at, ?)
                WHERE id=?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, status)
                ps.setString(2, result)
                ps.setString(3, error)
                ps.setString(4, errorType)
                if (completedAt != null) ps.setLong(5, completedAt) else ps.setNull(5, java.sql.Types.INTEGER)
                if (startedAt   != null) ps.setLong(6, startedAt)   else ps.setNull(6, java.sql.Types.INTEGER)
                ps.setString(7, id)
                ps.executeUpdate()
            }
        }
    }

    // ── incrementRetry：重试计数 ──────────────────────────────────────────────

    /**
     * 将 retry_count +1。
     * 在 WorkerEngine 将失败任务重置回 pending 之前调用，记录已消耗的重试次数。
     */
    override suspend fun incrementRetry(id: String): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                "UPDATE task SET retry_count = retry_count + 1 WHERE id = ?"
            ).use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
        }
    }

    // ── listByPipeline / listAll：查询 ────────────────────────────────────────

    /** 返回指定流水线的所有任务，按优先级 DESC、创建时间 ASC 排序。 */
    override suspend fun listByPipeline(pipelineId: String): List<Task> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<Task>()
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM task WHERE pipeline_id = ? ORDER BY priority DESC, created_at ASC"
                ).use { ps ->
                    ps.setString(1, pipelineId)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) result.add(rowToTask(rs))
                    }
                }
            }
            result
        }

    /**
     * 查询所有任务，支持可选过滤。
     * WHERE 子句根据传入参数动态拼接，避免占位符数量与实际参数不匹配。
     */
    override suspend fun listAll(pipelineId: String?, status: String?): List<Task> =
        withContext(Dispatchers.IO) {
            val result     = mutableListOf<Task>()
            val conditions = mutableListOf<String>()
            if (pipelineId != null) conditions.add("pipeline_id = ?")
            if (status     != null) conditions.add("status = ?")
            val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"

            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM task $where ORDER BY priority DESC, created_at ASC"
                ).use { ps ->
                    var idx = 1
                    if (pipelineId != null) ps.setString(idx++, pipelineId)
                    if (status     != null) ps.setString(idx,   status)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) result.add(rowToTask(rs))
                    }
                }
            }
            result
        }

    // ── create / createAll：插入 ──────────────────────────────────────────────

    /** 插入单个任务，委托给 createAll 实现。 */
    override suspend fun create(task: Task): Task {
        createAll(listOf(task))
        return task
    }

    /**
     * 批量插入任务。
     *
     * 使用 `INSERT OR IGNORE` 而非 `INSERT ... ON CONFLICT(id) DO NOTHING`：
     * - 前者忽略**任意** UNIQUE 约束冲突（包括 idempotency_key）
     * - 后者只忽略 id 冲突，idempotency_key 重复时仍会抛出异常
     */
    override suspend fun createAll(tasks: List<Task>): Unit = withContext(Dispatchers.IO) {
        if (tasks.isEmpty()) return@withContext
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                INSERT OR IGNORE INTO task (
                    id, type, pipeline_id, material_id, status, priority,
                    depends_on, cache_policy, payload, idempotency_key,
                    max_retries, created_by, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                for (t in tasks) {
                    ps.setString(1,  t.id)
                    ps.setString(2,  t.type)
                    ps.setString(3,  t.pipelineId)
                    ps.setString(4,  t.materialId)
                    ps.setString(5,  t.status)
                    ps.setInt(6,     t.priority)
                    ps.setString(7,  t.dependsOn)
                    ps.setString(8,  t.cachePolicy)
                    ps.setString(9,  t.payload)
                    ps.setString(10, t.idempotencyKey)
                    ps.setInt(11,    t.maxRetries)
                    ps.setString(12, t.createdBy)
                    ps.setLong(13,   t.createdAt)
                    ps.executeUpdate()
                }
            }
        }
    }

    // ── rowToTask：ResultSet → Task ───────────────────────────────────────────

    /**
     * 将 JDBC ResultSet 的当前行映射为 Task 对象。
     * 对可为空的 INTEGER 列用 getLong() + wasNull() 判断，防止 0 被误当 null。
     */
    private fun rowToTask(rs: java.sql.ResultSet): Task = Task(
        id                = rs.getString("id"),
        type              = rs.getString("type"),
        pipelineId        = rs.getString("pipeline_id"),
        materialId        = rs.getString("material_id"),
        status            = rs.getString("status"),
        priority          = rs.getInt("priority"),
        dependsOn         = rs.getString("depends_on"),
        cachePolicy       = rs.getString("cache_policy"),
        payload           = rs.getString("payload"),
        result            = rs.getString("result"),
        resultAckedAt     = rs.getLong("result_acked_at").takeIf  { !rs.wasNull() },
        error             = rs.getString("error"),
        errorType         = rs.getString("error_type"),
        excludedNodes     = rs.getString("excluded_nodes"),
        idempotencyKey    = rs.getString("idempotency_key"),
        retryCount        = rs.getInt("retry_count"),
        maxRetries        = rs.getInt("max_retries"),
        createdBy         = rs.getString("created_by"),
        claimedBy         = rs.getString("claimed_by"),
        originalClaimedBy = rs.getString("original_claimed_by"),
        fileNodeId        = rs.getString("file_node_id"),
        nodeAffinity      = rs.getString("node_affinity"),
        createdAt         = rs.getLong("created_at"),
        claimedAt         = rs.getLong("claimed_at").takeIf       { !rs.wasNull() },
        startedAt         = rs.getLong("started_at").takeIf       { !rs.wasNull() },
        completedAt       = rs.getLong("completed_at").takeIf     { !rs.wasNull() },
        heartbeatAt       = rs.getLong("heartbeat_at").takeIf     { !rs.wasNull() },
        staleAt           = rs.getLong("stale_at").takeIf         { !rs.wasNull() },
        reclaimedAt       = rs.getLong("reclaimed_at").takeIf     { !rs.wasNull() },
    )
}
