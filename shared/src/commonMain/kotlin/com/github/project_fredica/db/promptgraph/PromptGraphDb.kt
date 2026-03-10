package com.github.project_fredica.db.promptgraph

// =============================================================================
// PromptGraphDb —— prompt_graph_def / prompt_graph_run / prompt_node_run 的 JDBC 实现
// =============================================================================
//
// 管理三张表：
//   prompt_graph_def   — 提示词图蓝图（节点/边/Schema 均以 JSON 字符串存储）
//   prompt_graph_run   — 运行实例（含累积 context_json）
//   prompt_node_run    — 单节点执行记录（input_snapshot / output / override）
//
// 特殊方法 upsertSystemGraphs()：
//   从 classpath 资源目录加载系统内置 Graph JSON，以 INSERT OR REPLACE 写入 DB。
//   FredicaApi.jvm.kt 启动时调用（boot 完成后）。
// =============================================================================

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.error
import com.github.project_fredica.apputil.loadJsonModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

// =============================================================================
// PromptGraphDefDb
// =============================================================================

class PromptGraphDefDb(private val db: Database) : PromptGraphDefRepo {

    private val logger = createLogger()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS prompt_graph_def (
                        id                        TEXT    PRIMARY KEY,
                        name                      TEXT    NOT NULL,
                        description               TEXT,
                        nodes_json                TEXT    NOT NULL DEFAULT '[]',
                        edges_json                TEXT    NOT NULL DEFAULT '[]',
                        schema_registry_json      TEXT    NOT NULL DEFAULT '[]',
                        migrations_json           TEXT    NOT NULL DEFAULT '[]',
                        version                   INTEGER NOT NULL DEFAULT 1,
                        schema_version            TEXT    NOT NULL DEFAULT '1.0.0',
                        source_type               TEXT    NOT NULL DEFAULT 'user',
                        parent_def_id             TEXT,
                        parent_def_ver_at_fork    INTEGER,
                        parent_schema_ver_at_fork TEXT,
                        created_at                INTEGER NOT NULL,
                        updated_at                INTEGER NOT NULL
                    )
                """.trimIndent())
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_pgd_source ON prompt_graph_def(source_type)"
                )
            }
        }
    }

    /**
     * 从 classpath 资源目录 `prompt_graphs/` 加载系统内置 Graph JSON，
     * 以 INSERT OR REPLACE 写入 DB（每次启动均同步最新内置版本）。
     *
     * 资源文件命名规则：`prompt_graphs/{graph_id_slug}.json`
     * （graph_id 中的 `:` 替换为 `_`，如 `system_weben_video_concept_extract.json`）
     */
    suspend fun upsertSystemGraphs() = withContext(Dispatchers.IO) {
        val resourceNames = listOf("prompt_graphs/weben_video_concept_extract.json")
        for (resource in resourceNames) {
            val text = Thread.currentThread().contextClassLoader
                ?.getResourceAsStream(resource)
                ?.bufferedReader()
                ?.readText()
            if (text == null) {
                logger.warn("upsertSystemGraphs: 资源文件未找到 resource=$resource，跳过")
                continue
            }
            val def = text.loadJsonModel<PromptGraphDef>().getOrElse { e ->
                logger.error("upsertSystemGraphs: 解析失败 resource=$resource", e)
                return@withContext
            }
            upsert(def)
            logger.info("upsertSystemGraphs: 已同步系统图 id=${def.id} version=${def.version}")
        }
    }

    override suspend fun upsert(def: PromptGraphDef) = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("""
                INSERT INTO prompt_graph_def
                    (id, name, description, nodes_json, edges_json, schema_registry_json,
                     migrations_json, version, schema_version, source_type,
                     parent_def_id, parent_def_ver_at_fork, parent_schema_ver_at_fork,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    name                      = excluded.name,
                    description               = excluded.description,
                    nodes_json                = excluded.nodes_json,
                    edges_json                = excluded.edges_json,
                    schema_registry_json      = excluded.schema_registry_json,
                    migrations_json           = excluded.migrations_json,
                    version                   = excluded.version,
                    schema_version            = excluded.schema_version,
                    source_type               = excluded.source_type,
                    parent_def_id             = excluded.parent_def_id,
                    parent_def_ver_at_fork    = excluded.parent_def_ver_at_fork,
                    parent_schema_ver_at_fork = excluded.parent_schema_ver_at_fork,
                    updated_at                = excluded.updated_at
                    -- 注意：created_at 不在 UPDATE 列表中，保留最初创建时间。
                    -- 系统图每次启动升级版本时，不应重置 created_at。
            """.trimIndent()).use { ps ->
                ps.setString(1, def.id)
                ps.setString(2, def.name)
                ps.setStringOrNull(3, def.description)
                ps.setString(4, def.nodesJson)
                ps.setString(5, def.edgesJson)
                ps.setString(6, def.schemaRegistryJson)
                ps.setString(7, def.migrationsJson)
                ps.setInt(8, def.version)
                ps.setString(9, def.schemaVersion)
                ps.setString(10, def.sourceType)
                ps.setStringOrNull(11, def.parentDefId)
                ps.setIntOrNull(12, def.parentDefVerAtFork)
                ps.setStringOrNull(13, def.parentSchemaVerAtFork)
                ps.setLong(14, def.createdAt)
                ps.setLong(15, def.updatedAt)
                ps.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun getById(id: String): PromptGraphDef? = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("SELECT * FROM prompt_graph_def WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toDef() else null }
            }
        }
    }

    override suspend fun listAll(sourceType: String?, limit: Int, offset: Int): List<PromptGraphDef> =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                val sql = if (sourceType != null)
                    "SELECT * FROM prompt_graph_def WHERE source_type = ? ORDER BY updated_at DESC LIMIT ? OFFSET ?"
                else
                    "SELECT * FROM prompt_graph_def ORDER BY updated_at DESC LIMIT ? OFFSET ?"
                conn.prepareStatement(sql).use { ps ->
                    if (sourceType != null) {
                        ps.setString(1, sourceType); ps.setInt(2, limit); ps.setInt(3, offset)
                    } else {
                        ps.setInt(1, limit); ps.setInt(2, offset)
                    }
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toDef()) } }
                }
            }
        }

    override suspend fun deleteById(id: String) = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("DELETE FROM prompt_graph_def WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
        }
        Unit
    }

    private fun ResultSet.toDef() = PromptGraphDef(
        id                   = getString("id"),
        name                 = getString("name"),
        description          = getString("description"),
        nodesJson            = getString("nodes_json"),
        edgesJson            = getString("edges_json"),
        schemaRegistryJson   = getString("schema_registry_json"),
        migrationsJson       = getString("migrations_json"),
        version              = getInt("version"),
        schemaVersion        = getString("schema_version"),
        sourceType           = getString("source_type"),
        parentDefId          = getString("parent_def_id"),
        parentDefVerAtFork   = getInt("parent_def_ver_at_fork").takeIf { !wasNull() },
        parentSchemaVerAtFork = getString("parent_schema_ver_at_fork"),
        createdAt            = getLong("created_at"),
        updatedAt            = getLong("updated_at"),
    )
}

// =============================================================================
// PromptGraphRunDb
// =============================================================================

class PromptGraphRunDb(private val db: Database) : PromptGraphRunRepo {

    private val logger = createLogger()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS prompt_graph_run (
                        id                    TEXT    PRIMARY KEY,
                        prompt_graph_def_id   TEXT    NOT NULL,
                        graph_def_ver         INTEGER NOT NULL,
                        schema_version        TEXT    NOT NULL,
                        workflow_run_id       TEXT,
                        workflow_node_run_id  TEXT,
                        material_id           TEXT,
                        status                TEXT    NOT NULL DEFAULT 'pending',
                        context_json          TEXT    NOT NULL DEFAULT '{}',
                        created_at            INTEGER NOT NULL,
                        completed_at          INTEGER
                    )
                """.trimIndent())
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_pgr_def    ON prompt_graph_run(prompt_graph_def_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_pgr_wf_run ON prompt_graph_run(workflow_run_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_pgr_status ON prompt_graph_run(status)")
            }
        }
    }

    override suspend fun create(run: PromptGraphRun) = withContext(Dispatchers.IO) {
        logger.debug("PromptGraphRunDb.create: runId=${run.id} defId=${run.promptGraphDefId} status=${run.status}")
        db.useConnection { conn ->
            conn.prepareStatement("""
                INSERT INTO prompt_graph_run
                    (id, prompt_graph_def_id, graph_def_ver, schema_version,
                     workflow_run_id, workflow_node_run_id, material_id,
                     status, context_json, created_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO NOTHING
            """.trimIndent()).use { ps ->
                ps.setString(1, run.id)
                ps.setString(2, run.promptGraphDefId)
                ps.setInt(3, run.graphDefVer)
                ps.setString(4, run.schemaVersion)
                ps.setStringOrNull(5, run.workflowRunId)
                ps.setStringOrNull(6, run.workflowNodeRunId)
                ps.setStringOrNull(7, run.materialId)
                ps.setString(8, run.status)
                ps.setString(9, run.contextJson)
                ps.setLong(10, run.createdAt)
                ps.setLongOrNull(11, run.completedAt)
                ps.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun getById(id: String): PromptGraphRun? = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("SELECT * FROM prompt_graph_run WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toRun() else null }
            }
        }
    }

    override suspend fun listByDef(promptGraphDefId: String, limit: Int, offset: Int): List<PromptGraphRun> =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM prompt_graph_run WHERE prompt_graph_def_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?"
                ).use { ps ->
                    ps.setString(1, promptGraphDefId)
                    ps.setInt(2, limit)
                    ps.setInt(3, offset)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toRun()) } }
                }
            }
        }

    override suspend fun updateStatus(id: String, status: String, completedAt: Long?) =
        withContext(Dispatchers.IO) {
            logger.debug("PromptGraphRunDb.updateStatus: id=$id status=$status completedAt=$completedAt")
            db.useConnection { conn ->
                conn.prepareStatement(
                    "UPDATE prompt_graph_run SET status = ?, completed_at = ? WHERE id = ?"
                ).use { ps ->
                    ps.setString(1, status)
                    ps.setLongOrNull(2, completedAt)
                    ps.setString(3, id)
                    ps.executeUpdate()
                }
            }
            Unit
        }

    /**
     * 将 nodeKey=valueJson 合并到 context_json。
     *
     * 为什么不直接用 SQLite JSON_SET？
     * SQLite 的 JSON_SET(col, '$.key', value) 需要 key 在编译期已知，
     * 无法使用动态参数绑定的 key；JSON_PATCH 的 UPSERT 语义在嵌套场景不稳定。
     * 对于节点输出（通常 < 10KB），Kotlin 侧 read-modify-write 的性能完全可接受。
     */
    override suspend fun mergeContext(id: String, nodeKey: String, valueJson: String) =
        withContext(Dispatchers.IO) {
            logger.debug("PromptGraphRunDb.mergeContext: runId=$id key=$nodeKey valueLen=${valueJson.length}")
            db.useConnection { conn ->
                // 读取当前 context_json
                val current = conn.prepareStatement(
                    "SELECT context_json FROM prompt_graph_run WHERE id = ?"
                ).use { ps ->
                    ps.setString(1, id)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else "{}" }
                } ?: "{}"

                // 在 Kotlin 侧合并：将 nodeKey → valueJson 追加到现有对象
                val merged = mergeJsonKey(current, nodeKey, valueJson)

                conn.prepareStatement(
                    "UPDATE prompt_graph_run SET context_json = ? WHERE id = ?"
                ).use { ps ->
                    ps.setString(1, merged)
                    ps.setString(2, id)
                    ps.executeUpdate()
                }
            }
            Unit
        }

    override suspend fun setContext(id: String, contextJson: String) = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("UPDATE prompt_graph_run SET context_json = ? WHERE id = ?").use { ps ->
                ps.setString(1, contextJson)
                ps.setString(2, id)
                ps.executeUpdate()
            }
        }
        Unit
    }

    private fun ResultSet.toRun() = PromptGraphRun(
        id                 = getString("id"),
        promptGraphDefId   = getString("prompt_graph_def_id"),
        graphDefVer        = getInt("graph_def_ver"),
        schemaVersion      = getString("schema_version"),
        workflowRunId      = getString("workflow_run_id"),
        workflowNodeRunId  = getString("workflow_node_run_id"),
        materialId         = getString("material_id"),
        status             = getString("status"),
        contextJson        = getString("context_json"),
        createdAt          = getLong("created_at"),
        completedAt        = getLong("completed_at").takeIf { !wasNull() },
    )
}

// =============================================================================
// PromptNodeRunDb
// =============================================================================

class PromptNodeRunDb(private val db: Database) : PromptNodeRunRepo {

    private val logger = createLogger()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS prompt_node_run (
                        id                    TEXT    PRIMARY KEY,
                        prompt_graph_run_id   TEXT    NOT NULL,
                        node_def_id           TEXT    NOT NULL,
                        status                TEXT    NOT NULL DEFAULT 'pending',
                        input_snapshot_json   TEXT,
                        output_json           TEXT,
                        override_json         TEXT,
                        override_by           TEXT,
                        override_at           INTEGER,
                        override_note         TEXT,
                        downstream_policy     TEXT    NOT NULL DEFAULT 'INVALIDATE',
                        tokens_input          INTEGER,
                        tokens_output         INTEGER,
                        cost_usd              REAL,
                        created_at            INTEGER NOT NULL,
                        completed_at          INTEGER,
                        UNIQUE (prompt_graph_run_id, node_def_id)
                    )
                """.trimIndent())
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_pnr_run ON prompt_node_run(prompt_graph_run_id)"
                )
            }
        }
    }

    override suspend fun create(nodeRun: PromptNodeRun) = withContext(Dispatchers.IO) {
        logger.debug("PromptNodeRunDb.create: nodeId=${nodeRun.nodeDefId} runId=${nodeRun.promptGraphRunId}")
        db.useConnection { conn ->
            conn.prepareStatement("""
                INSERT INTO prompt_node_run
                    (id, prompt_graph_run_id, node_def_id, status,
                     input_snapshot_json, output_json, override_json,
                     override_by, override_at, override_note,
                     downstream_policy, tokens_input, tokens_output, cost_usd,
                     created_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(prompt_graph_run_id, node_def_id) DO NOTHING
                -- UNIQUE(prompt_graph_run_id, node_def_id) 保证同一 run 的同一节点只有一条记录。
                -- DO NOTHING 使 create() 幂等，防止引擎重入或并发调用时重复写入。
            """.trimIndent()).use { ps ->
                ps.setString(1, nodeRun.id)
                ps.setString(2, nodeRun.promptGraphRunId)
                ps.setString(3, nodeRun.nodeDefId)
                ps.setString(4, nodeRun.status)
                ps.setStringOrNull(5, nodeRun.inputSnapshotJson)
                ps.setStringOrNull(6, nodeRun.outputJson)
                ps.setStringOrNull(7, nodeRun.overrideJson)
                ps.setStringOrNull(8, nodeRun.overrideBy)
                ps.setLongOrNull(9, nodeRun.overrideAt)
                ps.setStringOrNull(10, nodeRun.overrideNote)
                ps.setString(11, nodeRun.downstreamPolicy)
                ps.setIntOrNull(12, nodeRun.tokensInput)
                ps.setIntOrNull(13, nodeRun.tokensOutput)
                ps.setDoubleOrNull(14, nodeRun.costUsd)
                ps.setLong(15, nodeRun.createdAt)
                ps.setLongOrNull(16, nodeRun.completedAt)
                ps.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun getById(id: String): PromptNodeRun? = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("SELECT * FROM prompt_node_run WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toNodeRun() else null }
            }
        }
    }

    override suspend fun getByRunAndNode(promptGraphRunId: String, nodeDefId: String): PromptNodeRun? =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM prompt_node_run WHERE prompt_graph_run_id = ? AND node_def_id = ?"
                ).use { ps ->
                    ps.setString(1, promptGraphRunId)
                    ps.setString(2, nodeDefId)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.toNodeRun() else null }
                }
            }
        }

    override suspend fun listByRun(promptGraphRunId: String): List<PromptNodeRun> =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM prompt_node_run WHERE prompt_graph_run_id = ? ORDER BY created_at ASC"
                ).use { ps ->
                    ps.setString(1, promptGraphRunId)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toNodeRun()) } }
                }
            }
        }

    override suspend fun updateStatus(id: String, status: String, completedAt: Long?) =
        withContext(Dispatchers.IO) {
            logger.debug("PromptNodeRunDb.updateStatus: id=$id status=$status")
            db.useConnection { conn ->
                conn.prepareStatement(
                    "UPDATE prompt_node_run SET status = ?, completed_at = ? WHERE id = ?"
                ).use { ps ->
                    ps.setString(1, status)
                    ps.setLongOrNull(2, completedAt)
                    ps.setString(3, id)
                    ps.executeUpdate()
                }
            }
            Unit
        }

    override suspend fun setInputSnapshot(id: String, inputSnapshotJson: String) =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "UPDATE prompt_node_run SET input_snapshot_json = ? WHERE id = ?"
                ).use { ps ->
                    ps.setString(1, inputSnapshotJson)
                    ps.setString(2, id)
                    ps.executeUpdate()
                }
            }
            Unit
        }

    override suspend fun setOutput(id: String, outputJson: String, tokensInput: Int?, tokensOutput: Int?) =
        withContext(Dispatchers.IO) {
            logger.debug("PromptNodeRunDb.setOutput: id=$id outputLen=${outputJson.length} tokens=(in=$tokensInput, out=$tokensOutput)")
            db.useConnection { conn ->
                conn.prepareStatement(
                    "UPDATE prompt_node_run SET output_json = ?, tokens_input = ?, tokens_output = ? WHERE id = ?"
                ).use { ps ->
                    ps.setString(1, outputJson)
                    ps.setIntOrNull(2, tokensInput)
                    ps.setIntOrNull(3, tokensOutput)
                    ps.setString(4, id)
                    ps.executeUpdate()
                }
            }
            Unit
        }

    override suspend fun setOverride(
        id: String,
        overrideJson: String,
        overrideBy: String,
        overrideAt: Long,
        overrideNote: String?,
    ) = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("""
                UPDATE prompt_node_run
                SET override_json = ?, override_by = ?, override_at = ?,
                    override_note = ?, status = 'overridden'
                WHERE id = ?
            """.trimIndent()).use { ps ->
                ps.setString(1, overrideJson)
                ps.setString(2, overrideBy)
                ps.setLong(3, overrideAt)
                ps.setStringOrNull(4, overrideNote)
                ps.setString(5, id)
                ps.executeUpdate()
            }
        }
        Unit
    }

    private fun ResultSet.toNodeRun() = PromptNodeRun(
        id                = getString("id"),
        promptGraphRunId  = getString("prompt_graph_run_id"),
        nodeDefId         = getString("node_def_id"),
        status            = getString("status"),
        inputSnapshotJson = getString("input_snapshot_json"),
        outputJson        = getString("output_json"),
        overrideJson      = getString("override_json"),
        overrideBy        = getString("override_by"),
        overrideAt        = getLong("override_at").takeIf { !wasNull() },
        overrideNote      = getString("override_note"),
        downstreamPolicy  = getString("downstream_policy"),
        tokensInput       = getInt("tokens_input").takeIf { !wasNull() },
        tokensOutput      = getInt("tokens_output").takeIf { !wasNull() },
        costUsd           = getDouble("cost_usd").takeIf { !wasNull() },
        createdAt         = getLong("created_at"),
        completedAt       = getLong("completed_at").takeIf { !wasNull() },
    )
}

// =============================================================================
// 工具函数
// =============================================================================

/**
 * 将 key=valueJson 合并到现有 JSON 对象字符串（Kotlin 侧简单实现）。
 *
 * 策略：在 closing `}` 前插入 `,"key":valueJson`。
 * 若 current 不以 `}` 结尾（异常格式），回退为 `{"key":valueJson}`。
 *
 * 前提假设：调用方（引擎）保证同一 key 在同一 run 内只写入一次，
 * 因此不处理重复 key 的情况（重复写入会产生两个同名 key，但不影响后续 JSON 解析，
 * 因为解析器通常取最后一个值，而且引擎不会在同一 run 中重复执行同一节点）。
 */
internal fun mergeJsonKey(current: String, key: String, valueJson: String): String {
    val trimmed = current.trim()
    // 转义 key 中的特殊字符（key 通常是 node_def_id，全是 ASCII）
    val escapedKey = key.replace("\\", "\\\\").replace("\"", "\\\"")
    return if (trimmed == "{}" || trimmed == "{ }") {
        "{\"$escapedKey\":$valueJson}"
    } else if (trimmed.endsWith("}")) {
        "${trimmed.dropLast(1)},\"$escapedKey\":$valueJson}"
    } else {
        "{\"$escapedKey\":$valueJson}"
    }
}

private fun PreparedStatement.setStringOrNull(idx: Int, v: String?) {
    if (v != null) setString(idx, v) else setNull(idx, Types.VARCHAR)
}

private fun PreparedStatement.setIntOrNull(idx: Int, v: Int?) {
    if (v != null) setInt(idx, v) else setNull(idx, Types.INTEGER)
}

private fun PreparedStatement.setLongOrNull(idx: Int, v: Long?) {
    if (v != null) setLong(idx, v) else setNull(idx, Types.INTEGER)
}

private fun PreparedStatement.setDoubleOrNull(idx: Int, v: Double?) {
    if (v != null) setDouble(idx, v) else setNull(idx, Types.REAL)
}
