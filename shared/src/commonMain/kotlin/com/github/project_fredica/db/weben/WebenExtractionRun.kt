package com.github.project_fredica.db.weben

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.ktorm.database.Database
import java.sql.ResultSet
import java.sql.Types

/**
 * 单次概念提取运行记录。
 *
 * 保存从 summary.weben 页面触发的一次完整概念提取的上下文：
 * 提示词脚本、解析后 Prompt 文本、LLM 输入/输出，以及本次用户审核通过的概念数量。
 *
 * source_id 指向 WebenSource，每个来源可有多次提取运行历史。
 */
@Serializable
data class WebenExtractionRun(
    val id: String,
    @SerialName("source_id") val sourceId: String,
    @SerialName("material_id") val materialId: String? = null,
    @SerialName("prompt_script") val promptScript: String? = null,
    @SerialName("prompt_text") val promptText: String? = null,
    @SerialName("llm_model_id") val llmModelId: String? = null,
    @SerialName("llm_input_json") val llmInputJson: String? = null,
    @SerialName("llm_output_raw") val llmOutputRaw: String? = null,
    @SerialName("concept_count") val conceptCount: Int = 0,
    @SerialName("created_at") val createdAt: Long,
)

interface WebenExtractionRunRepo {
    suspend fun create(run: WebenExtractionRun)
    suspend fun getById(id: String): WebenExtractionRun?
    /** 按来源分页，最新的在前。 */
    suspend fun listBySourceId(sourceId: String, limit: Int = 20, offset: Int = 0): List<WebenExtractionRun>
    suspend fun countBySourceId(sourceId: String): Int
}

object WebenExtractionRunService {
    private var _repo: WebenExtractionRunRepo? = null
    val repo: WebenExtractionRunRepo
        get() = _repo ?: error("WebenExtractionRunService 未初始化，请先调用 initialize()")
    fun initialize(repo: WebenExtractionRunRepo) { _repo = repo }
}

// =============================================================================
// WebenExtractionRunDb —— weben_extraction_run 表的 JDBC 实现
// =============================================================================

class WebenExtractionRunDb(private val db: Database) : WebenExtractionRunRepo {

    suspend fun initialize() = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS weben_extraction_run (
                        id              TEXT    PRIMARY KEY,
                        source_id       TEXT    NOT NULL,
                        material_id     TEXT,
                        prompt_script   TEXT,
                        prompt_text     TEXT,
                        llm_model_id    TEXT,
                        llm_input_json  TEXT,
                        llm_output_raw  TEXT,
                        concept_count   INTEGER NOT NULL DEFAULT 0,
                        created_at      INTEGER NOT NULL
                    )
                """.trimIndent())
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_wer_source ON weben_extraction_run(source_id, created_at DESC)"
                )
            }
        }
    }

    override suspend fun create(run: WebenExtractionRun) = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("""
                INSERT INTO weben_extraction_run
                    (id, source_id, material_id, prompt_script, prompt_text,
                     llm_model_id, llm_input_json, llm_output_raw, concept_count, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO NOTHING
            """.trimIndent()).use { ps ->
                ps.setString(1, run.id)
                ps.setString(2, run.sourceId)
                ps.setStringOrNull(3, run.materialId)
                ps.setStringOrNull(4, run.promptScript?.take(65536))  // 截断防 OOM
                ps.setStringOrNull(5, run.promptText?.take(65536))
                ps.setStringOrNull(6, run.llmModelId)
                ps.setStringOrNull(7, run.llmInputJson?.take(65536))
                ps.setStringOrNull(8, run.llmOutputRaw?.take(65536))
                ps.setInt(9, run.conceptCount)
                ps.setLong(10, run.createdAt)
                ps.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun getById(id: String): WebenExtractionRun? = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("SELECT * FROM weben_extraction_run WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toRun() else null }
            }
        }
    }

    override suspend fun listBySourceId(sourceId: String, limit: Int, offset: Int): List<WebenExtractionRun> =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM weben_extraction_run WHERE source_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?"
                ).use { ps ->
                    ps.setString(1, sourceId)
                    ps.setInt(2, limit)
                    ps.setInt(3, offset)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toRun()) } }
                }
            }
        }

    override suspend fun countBySourceId(sourceId: String): Int = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM weben_extraction_run WHERE source_id = ?"
            ).use { ps ->
                ps.setString(1, sourceId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }
    }

    private fun ResultSet.toRun() = WebenExtractionRun(
        id            = getString("id"),
        sourceId      = getString("source_id"),
        materialId    = getString("material_id"),
        promptScript  = getString("prompt_script"),
        promptText    = getString("prompt_text"),
        llmModelId    = getString("llm_model_id"),
        llmInputJson  = getString("llm_input_json"),
        llmOutputRaw  = getString("llm_output_raw"),
        conceptCount  = getInt("concept_count"),
        createdAt     = getLong("created_at"),
    )
}

private fun java.sql.PreparedStatement.setStringOrNull(idx: Int, v: String?) {
    if (v != null) setString(idx, v) else setNull(idx, Types.VARCHAR)
}
