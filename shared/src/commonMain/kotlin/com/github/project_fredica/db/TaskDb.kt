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
//   3. recalculate() 不在此类实现：它要写 workflow_run 表，
//      职责属于 WorkflowRunDb，不应让 TaskDb 跨表依赖。
// =============================================================================

import com.github.project_fredica.apputil.createLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database

class TaskDb(private val db: Database) : TaskRepo {

    private val logger = createLogger()

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
                            workflow_run_id     TEXT NOT NULL,
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
                            idempotency_key     TEXT,
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
                    // Schema migration: add progress column if it doesn't exist yet
                    @Suppress("SwallowedException")
                    try {
                        stmt.execute("ALTER TABLE task ADD COLUMN progress INTEGER NOT NULL DEFAULT 0")
                    } catch (_: Exception) { /* column already exists */ }
                    // Schema migration: add is_paused column if it doesn't exist yet
                    @Suppress("SwallowedException")
                    try {
                        stmt.execute("ALTER TABLE task ADD COLUMN is_paused INTEGER NOT NULL DEFAULT 0")
                    } catch (_: Exception) { /* column already exists */ }
                    // Schema migration: add is_pausable column if it doesn't exist yet
                    @Suppress("SwallowedException")
                    try {
                        stmt.execute("ALTER TABLE task ADD COLUMN is_pausable INTEGER NOT NULL DEFAULT 1")
                    } catch (_: Exception) { /* column already exists */ }
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

    // ── updateProgress：更新任务进度 ─────────────────────────────────────────

    override suspend fun updateProgress(id: String, progress: Int): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("UPDATE task SET progress = ? WHERE id = ?").use { ps ->
                ps.setInt(1, progress.coerceIn(0, 100))
                ps.setString(2, id)
                ps.executeUpdate()
            }
        }
    }

    // ── updatePaused：更新暂停状态 ───────────────────────────────────────────

    override suspend fun updatePaused(id: String, paused: Boolean): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("UPDATE task SET is_paused = ? WHERE id = ?").use { ps ->
                ps.setInt(1, if (paused) 1 else 0)
                ps.setString(2, id)
                ps.executeUpdate()
            }
        }
    }

    // ── updatePausable：更新可暂停标志 ────────────────────────────────────────

    override suspend fun updatePausable(id: String, pausable: Boolean): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("UPDATE task SET is_pausable = ? WHERE id = ?").use { ps ->
                ps.setInt(1, if (pausable) 1 else 0)
                ps.setString(2, id)
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

    // ── listByWorkflowRun / listAll：查询 ────────────────────────────────────

    /** 返回指定工作流运行实例的所有任务，按优先级 DESC、创建时间 ASC 排序。 */
    override suspend fun listByWorkflowRun(workflowRunId: String): List<Task> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<Task>()
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM task WHERE workflow_run_id = ? ORDER BY priority DESC, created_at ASC"
                ).use { ps ->
                    ps.setString(1, workflowRunId)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) result.add(rowToTask(rs))
                    }
                }
            }
            result
        }

    /**
     * 查询所有任务，支持可选过滤 + 分页 + 排序，返回 [TaskListResult]。
     * WHERE 子句根据传入参数动态拼接，特殊 status 值（pending/running/paused）
     * 会展开为对应的 SQL 条件，无需额外绑定参数。
     */
    override suspend fun listAll(
        taskId: String?,
        workflowRunId: String?,
        status: String?,
        materialId: String?,
        categoryId: String?,
        page: Int,
        pageSize: Int,
        sortDesc: Boolean,
    ): TaskListResult = withContext(Dispatchers.IO) {
        // Build (sql, params) pairs in order — params must match '?' placeholders
        val conditions = mutableListOf<Pair<String, List<String>>>()
        if (taskId     != null) conditions.add("t.id = ?" to listOf(taskId))
        if (workflowRunId != null) conditions.add("t.workflow_run_id = ?" to listOf(workflowRunId))
        if (materialId != null) conditions.add("t.material_id = ?" to listOf(materialId))
        if (categoryId != null) conditions.add(
            "EXISTS (SELECT 1 FROM material_category_rel mcr WHERE mcr.material_id = t.material_id AND mcr.category_id = ?)" to listOf(categoryId)
        )
        if (status != null) when (status) {
            "pending" -> conditions.add("(t.status = 'pending' OR t.status = 'claimed')" to emptyList())
            "running" -> conditions.add("(t.status = 'running' AND t.is_paused = 0)"     to emptyList())
            "paused"  -> conditions.add("(t.status = 'running' AND t.is_paused = 1)"     to emptyList())
            else      -> conditions.add("t.status = ?" to listOf(status))
        }

        val where     = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ") { it.first }}"
        val allParams = conditions.flatMap { it.second }
        val order     = if (sortDesc) "DESC" else "ASC"
        val offset    = (page - 1).coerceAtLeast(0) * pageSize

        fun bindAll(ps: java.sql.PreparedStatement, extraInts: List<Int> = emptyList()) {
            allParams.forEachIndexed { i, v -> ps.setString(i + 1, v) }
            extraInts.forEachIndexed  { i, v -> ps.setInt(allParams.size + i + 1, v) }
        }

        var total = 0
        val items = mutableListOf<Task>()

        db.useConnection { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM task t $where").use { ps ->
                bindAll(ps)
                ps.executeQuery().use { rs -> if (rs.next()) total = rs.getInt(1) }
            }
            conn.prepareStatement(
                "SELECT t.* FROM task t $where ORDER BY t.created_at $order LIMIT ? OFFSET ?"
            ).use { ps ->
                bindAll(ps, listOf(pageSize, offset))
                ps.executeQuery().use { rs -> while (rs.next()) items.add(rowToTask(rs)) }
            }
        }

        TaskListResult(items = items, total = total)
    }

    // ── findById：按 ID 查询 ───────────────────────────────────────────────────

    override suspend fun findById(id: String): Task? = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("SELECT * FROM task WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rowToTask(rs) else null
                }
            }
        }
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
     * 幂等键语义：只对"活跃任务"（非终态）去重。
     * 若相同 idempotency_key 已存在于 pending/claimed/running 状态的任务中，则跳过插入。
     * 已完成（completed/failed/cancelled）的任务不阻塞新任务创建，重新执行交由 canSkip 决定。
     *
     * 使用 `INSERT OR IGNORE` 处理 id 级别的重复（防御性保护）。
     */
    override suspend fun createAll(tasks: List<Task>): Unit = withContext(Dispatchers.IO) {
        if (tasks.isEmpty()) return@withContext
        db.useConnection { conn ->
            // 收集本批次中有幂等键的任务，查出哪些 key 已有活跃任务
            val keysToCheck = tasks.mapNotNull { it.idempotencyKey }
            val activeKeys = if (keysToCheck.isEmpty()) emptySet() else {
                val placeholders = keysToCheck.joinToString(",") { "?" }
                conn.prepareStatement(
                    "SELECT idempotency_key FROM task WHERE idempotency_key IN ($placeholders) AND status NOT IN ('completed','failed','cancelled')"
                ).use { ps ->
                    keysToCheck.forEachIndexed { i, k -> ps.setString(i + 1, k) }
                    ps.executeQuery().use { rs ->
                        val result = mutableSetOf<String>()
                        while (rs.next()) result.add(rs.getString(1))
                        result
                    }
                }
            }

            conn.prepareStatement(
                """
                INSERT OR IGNORE INTO task (
                    id, type, workflow_run_id, material_id, status, priority,
                    depends_on, cache_policy, payload, idempotency_key,
                    max_retries, created_by, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                for (t in tasks) {
                    // 幂等键已有活跃任务则跳过
                    if (t.idempotencyKey != null && t.idempotencyKey in activeKeys) {
                        logger.info("Task skipped: idempotency_key='${t.idempotencyKey}' already has an active task")
                        continue
                    }
                    ps.setString(1,  t.id)
                    ps.setString(2,  t.type)
                    ps.setString(3,  t.workflowRunId)
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
        workflowRunId     = rs.getString("workflow_run_id"),
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
        progress          = rs.getInt("progress"),
        isPaused          = rs.getInt("is_paused") != 0,
        isPausable        = rs.getInt("is_pausable") != 0,
    )

    // ── listByType：按任务类型查询 ────────────────────────────────────────────

    /** 按任务类型查询所有任务，按创建时间降序排列。 */
    override suspend fun listByType(type: String): List<Task> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Task>()
        db.useConnection { conn ->
            conn.prepareStatement(
                "SELECT * FROM task WHERE type = ? ORDER BY created_at DESC"
            ).use { ps ->
                ps.setString(1, type)
                ps.executeQuery().use { rs ->
                    while (rs.next()) result.add(rowToTask(rs))
                }
            }
        }
        result
    }

    // ── listWorkflowRunIdsByType：按任务类型查去重 workflow_run_id ─────────────

    /**
     * 按任务类型查询去重的 workflow_run_id 列表，按该 workflow 下最新任务的创建时间降序，支持分页。
     *
     * 实现思路：
     *   1. COUNT(DISTINCT workflow_run_id) 得到总去重数
     *   2. GROUP BY workflow_run_id + ORDER BY MAX(created_at) DESC 实现去重并按最新活动排序
     *   3. LIMIT/OFFSET 分页
     */
    override suspend fun listWorkflowRunIdsByType(
        type: String,
        page: Int,
        pageSize: Int,
    ): WorkflowRunIdListResult = withContext(Dispatchers.IO) {
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        var total = 0
        val ids = mutableListOf<String>()
        db.useConnection { conn ->
            conn.prepareStatement(
                "SELECT COUNT(DISTINCT workflow_run_id) FROM task WHERE type = ?"
            ).use { ps ->
                ps.setString(1, type)
                ps.executeQuery().use { rs -> if (rs.next()) total = rs.getInt(1) }
            }
            conn.prepareStatement(
                """
                SELECT workflow_run_id FROM task
                WHERE type = ?
                GROUP BY workflow_run_id
                ORDER BY MAX(created_at) DESC
                LIMIT ? OFFSET ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, type)
                ps.setInt(2, pageSize)
                ps.setInt(3, offset)
                ps.executeQuery().use { rs ->
                    while (rs.next()) ids.add(rs.getString("workflow_run_id"))
                }
            }
        }
        logger.debug("[listWorkflowRunIdsByType] type=$type page=$page pageSize=$pageSize → ${ids.size}/$total")
        WorkflowRunIdListResult(ids = ids, total = total)
    }

    // ── snapshotNonTerminalTasks：快照非终态任务 ──────────────────────────────

    /**
     * 快照所有非终态任务（pending / claimed / running），在 resetStaleTasks() 之前调用。
     * 返回包含原始状态的完整任务列表，供 RestartTaskLog 记录使用。
     */
    override suspend fun snapshotNonTerminalTasks(): List<Task> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Task>()
        db.useConnection { conn ->
            conn.prepareStatement(
                "SELECT * FROM task WHERE status IN ('running', 'claimed', 'pending')"
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) result.add(rowToTask(rs))
                }
            }
        }
        result
    }

    // ── failOrphanedTasks：孤立任务对账 ───────────────────────────────────────

    /**
     * 找出所有孤立任务（workflow_run_id 不存在于 workflow_run 表中且未终态），
     * 批量标记为 failed。
     *
     * SQL 核心：子查询 `NOT IN (SELECT id FROM workflow_run)` 一次性找出
     * 全部孤立 Task，避免逐条查询。已终态的任务通过 `NOT IN (terminal statuses)`
     * 过滤，防止重复更新（幂等）。
     */
    override suspend fun failOrphanedTasks(): List<String> = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            val nowSec = System.currentTimeMillis() / 1000L
            val affected = mutableListOf<String>()

            // 1. 找出所有孤立且非终态的 Task ID
            conn.prepareStatement(
                """
                SELECT id FROM task
                WHERE status NOT IN ('completed', 'failed', 'cancelled')
                  AND workflow_run_id NOT IN (SELECT id FROM workflow_run)
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) affected.add(rs.getString("id"))
                }
            }

            if (affected.isEmpty()) return@useConnection affected

            // 2. 批量标记为 failed（用子查询，不拼接占位符，防止 IN 列表过长）
            conn.prepareStatement(
                """
                UPDATE task
                SET status = 'failed',
                    error = 'ORPHANED_NO_WORKFLOW_RUN',
                    error_type = 'ORPHANED',
                    completed_at = COALESCE(completed_at, ?)
                WHERE status NOT IN ('completed', 'failed', 'cancelled')
                  AND workflow_run_id NOT IN (SELECT id FROM workflow_run)
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, nowSec)
                ps.executeUpdate()
            }

            affected
        }
    }

    // ── cancelPendingTasksByWorkflowRun：级联取消等待中的任务 ─────────────────

    /**
     * 将指定 WorkflowRun 下所有 pending / claimed 任务批量标记为 cancelled。
     *
     * running 任务不在处理范围：running 任务已被 Executor 持有，
     * 必须通过 TaskCancelService 发送取消信号让 Executor 主动退出，
     * 直接修改 DB 状态会导致 Executor 与 DB 状态不一致。
     */
    override suspend fun cancelPendingTasksByWorkflowRun(workflowRunId: String): List<String> =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                val nowSec = System.currentTimeMillis() / 1000L
                val affected = mutableListOf<String>()

                // 1. 收集待取消的 Task ID（只取等待中的）
                conn.prepareStatement(
                    """
                    SELECT id FROM task
                    WHERE workflow_run_id = ?
                      AND status IN ('pending', 'claimed')
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, workflowRunId)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) affected.add(rs.getString("id"))
                    }
                }

                if (affected.isEmpty()) return@useConnection affected

                // 2. 批量取消
                conn.prepareStatement(
                    """
                    UPDATE task
                    SET status = 'cancelled',
                        completed_at = COALESCE(completed_at, ?)
                    WHERE workflow_run_id = ?
                      AND status IN ('pending', 'claimed')
                    """.trimIndent()
                ).use { ps ->
                    ps.setLong(1, nowSec)
                    ps.setString(2, workflowRunId)
                    ps.executeUpdate()
                }

                affected
            }
        }

    // ── cancelBlockedTasks：依赖失败级联取消 ──────────────────────────────────

    /**
     * 将指定 WorkflowRun 下所有"被阻塞"的 pending 任务批量标记为 cancelled。
     *
     * "被阻塞"定义：depends_on 中存在至少一个状态为 failed 或 cancelled 的前置任务。
     *
     * SQL 核心逻辑：
     * ```sql
     * UPDATE task SET status='cancelled', ...
     * WHERE workflow_run_id = ?
     *   AND status = 'pending'
     *   AND EXISTS (
     *     SELECT 1 FROM task dep
     *     JOIN json_each(task.depends_on) je ON dep.id = je.value
     *     WHERE dep.status IN ('failed', 'cancelled')
     *   )
     * ```
     * `json_each(task.depends_on)` 展开 JSON 数组，JOIN task 表检查前置任务状态。
     * `EXISTS(前置任务已终止)` 找出所有被阻塞的 pending 任务。
     *
     * 注意：只处理 pending 任务，不干预 claimed/running（已被 worker 持有）。
     * 由 WorkflowRunDb.recalculate() 在统计状态前调用，确保状态一致性。
     */
    override suspend fun cancelBlockedTasks(workflowRunId: String): List<String> =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                val nowSec = System.currentTimeMillis() / 1000L
                val affected = mutableListOf<String>()

                // 1. 找出所有被阻塞的 pending 任务 ID
                conn.prepareStatement(
                    """
                    SELECT id FROM task
                    WHERE workflow_run_id = ?
                      AND status = 'pending'
                      AND EXISTS (
                        SELECT 1 FROM task dep
                        JOIN json_each(task.depends_on) je ON dep.id = je.value
                        WHERE dep.status IN ('failed', 'cancelled')
                      )
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, workflowRunId)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) affected.add(rs.getString("id"))
                    }
                }

                if (affected.isEmpty()) return@useConnection affected

                // 2. 批量取消（用子查询，避免拼接 IN 列表）
                conn.prepareStatement(
                    """
                    UPDATE task
                    SET status = 'cancelled',
                        error = 'DEPENDENCY_FAILED',
                        error_type = 'DEPENDENCY_FAILED',
                        completed_at = COALESCE(completed_at, ?)
                    WHERE workflow_run_id = ?
                      AND status = 'pending'
                      AND EXISTS (
                        SELECT 1 FROM task dep
                        JOIN json_each(task.depends_on) je ON dep.id = je.value
                        WHERE dep.status IN ('failed', 'cancelled')
                      )
                    """.trimIndent()
                ).use { ps ->
                    ps.setLong(1, nowSec)
                    ps.setString(2, workflowRunId)
                    ps.executeUpdate()
                }

                affected
            }
        }

    // ── resetStaleTasks：启动时清理僵尸任务 ───────────────────────────────────

    /**
     * 应用重启时将所有非终态任务一律取消，防止任务永久卡在 pending/claimed/running。
     *
     * 设计决策：取消（cancelled）而非失败（failed）
     *   - running：执行被中途打断，结果未知。此前标记为 failed，但这并非任务自身错误，
     *              而是外部中断，改为 cancelled 语义更准确，也不会触发 WorkflowRun 变 failed。
     *   - claimed：已被 worker 认领但尚未开始执行，同样取消。
     *   - pending ：等待依赖的下游任务。由于其上游（running/claimed）已被取消，
     *              claimNext() 的 DAG 语义要求所有 depends_on 任务均 completed 才可认领，
     *              上游一旦取消，该任务将永久阻塞——必须同步取消，否则 WorkflowRun 状态
     *              将永远停留在 running，且 recalculate() 无法将其修正为终态。
     *
     * 恢复策略：若用户希望重新执行，应由业务层创建参数相同的新任务（新 WorkflowRun），
     *           而非尝试恢复原任务。"恢复"操作的入口在任务中心的 UI 层面。
     *
     * @return 受影响的 workflowRunId 集合（用于后续调用 recalculate() 修正汇总状态）
     */
    override suspend fun resetStaleTasks(): Set<String> = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            val nowSec = System.currentTimeMillis() / 1000L
            val affected = mutableSetOf<String>()

            // 1. 收集所有非终态任务的 workflowRunId（pending / claimed / running 三种状态）
            //    pending 必须纳入：其上游将被取消，claimNext DAG 语义决定它永远不会被认领
            conn.prepareStatement(
                "SELECT DISTINCT workflow_run_id FROM task WHERE status IN ('running', 'claimed', 'pending')"
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) affected.add(rs.getString(1))
                }
            }

            if (affected.isEmpty()) return@useConnection affected

            // 2. running → cancelled（执行中途被中断；取消而非 failed，语义更准确）
            conn.prepareStatement(
                """
                UPDATE task
                SET status='cancelled', error='APP_RESTARTED', error_type='APP_RESTART',
                    completed_at=COALESCE(completed_at, ?)
                WHERE status='running'
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, nowSec)
                ps.executeUpdate()
            }

            // 3. claimed → cancelled（已认领但尚未开始执行，同样取消）
            conn.prepareStatement(
                "UPDATE task SET status='cancelled', completed_at=COALESCE(completed_at, ?) WHERE status='claimed'"
            ).use { ps ->
                ps.setLong(1, nowSec)
                ps.executeUpdate()
            }

            // 4. pending → cancelled（等待中的下游任务，上游已取消，永远无法通过 DAG 校验）
            conn.prepareStatement(
                "UPDATE task SET status='cancelled', completed_at=COALESCE(completed_at, ?) WHERE status='pending'"
            ).use { ps ->
                ps.setLong(1, nowSec)
                ps.executeUpdate()
            }

            affected
        }
    }
}
