package com.github.project_fredica.db.weben

// =============================================================================
// WebenConcept —— 概念节点
// =============================================================================
//
// 管理三张表：
//   weben_concept        — 概念节点（mastery 为聚合缓存，禁止应用层直接写入）
//   weben_concept_alias  — 概念别名（用于去重合并时的模糊匹配）
//   weben_concept_source — 概念-来源时间点关联（M:N）
// =============================================================================

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.ktorm.database.Database
import java.sql.ResultSet
import java.sql.Types

@Serializable
data class WebenConcept(
    /** UUID，概念唯一标识。 */
    val id: String,
    /** 规范化名称（去标点小写后的去重键，同时作为展示名）。 */
    @SerialName("canonical_name") val canonicalName: String,
    /** 概念类型：'理论'|'术语'|'硬件经验'|'开发经验'|'方法技巧'|'工具软件'|'器件芯片'|'协议'|'公式'|'设计模式'。 */
    @SerialName("concept_type") val conceptType: String,
    /** AI 生成的简短定义，用户可手动修正；null 表示尚未生成。 */
    @SerialName("brief_definition") val briefDefinition: String? = null,
    /** 类型特定结构化元数据（公式变量说明、器件厂商参数等）。 */
    @SerialName("metadata_json") val metadataJson: String = "{}",
    /** AI 提取置信度（0-1），用户手动添加的概念固定为 1.0。 */
    val confidence: Double = 1.0,
    /** 【只读缓存】该概念所有闪卡 ease_factor 的归一化均值（0-1），禁止应用层直接写入。 */
    val mastery: Double = 0.0,
    /** 首次在来源中出现的时间，Unix 秒；一旦写入不可更改。 */
    @SerialName("first_seen_at") val firstSeenAt: Long,
    /** 最近一次新增来源关联的时间，Unix 秒；每次关联新来源时更新。 */
    @SerialName("last_seen_at") val lastSeenAt: Long,
    /** 记录创建时间，Unix 秒。 */
    @SerialName("created_at") val createdAt: Long,
    /** 记录最后修改时间（用户编辑定义/元数据时更新），Unix 秒。 */
    @SerialName("updated_at") val updatedAt: Long,
)

@Serializable
data class WebenConceptAlias(
    /** 自增主键，供 API 按 id 删除单条别名。 */
    val id: Long = 0,
    /** 所属概念（weben_concept.id）。 */
    @SerialName("concept_id") val conceptId: String,
    /** 别名文本（如缩写、英文原名、常见误拼）。 */
    val alias: String,
    /** 别名来源描述：'LLM提取' | '用户添加' | 来源标题等。 */
    @SerialName("alias_source") val aliasSource: String? = null,
)

@Serializable
data class WebenConceptSource(
    /** 自增主键。 */
    val id: Long = 0,
    /** 所属概念（weben_concept.id）。 */
    @SerialName("concept_id") val conceptId: String,
    /** 来源（weben_source.id）。 */
    @SerialName("source_id") val sourceId: String,
    /** 概念在视频中出现的精确时间点（秒），文章/文件来源为 null。 */
    @SerialName("timestamp_sec") val timestampSec: Double? = null,
    /** 来源原文摘录，供图谱追溯时展示。 */
    val excerpt: String? = null,
)

// =============================================================================
// WebenConceptRepo
// =============================================================================

interface WebenConceptRepo {
    suspend fun create(concept: WebenConcept)
    suspend fun getById(id: String): WebenConcept?
    suspend fun getByCanonicalName(canonicalName: String): WebenConcept?
    /** 瀑布流分页：可按 conceptType 过滤，按 mastery 升序（掌握度低的优先）。 */
    suspend fun listAll(conceptType: String? = null, limit: Int = 50, offset: Int = 0): List<WebenConcept>
    /** 更新概念定义与元数据（用户手动修正），同步更新 updated_at。 */
    suspend fun update(concept: WebenConcept)
    /** 【内部】由 WebenFlashcardDb 在每次闪卡评分后调用，更新聚合缓存。 */
    suspend fun updateMastery(id: String, mastery: Double)
    // — 别名 —
    /** 插入别名，UNIQUE(concept_id, alias) 冲突时忽略。 */
    suspend fun addAlias(alias: WebenConceptAlias)
    suspend fun listAliases(conceptId: String): List<WebenConceptAlias>
    suspend fun deleteAlias(id: Long)
    // — 来源关联 —
    /** 插入概念-来源关联；去重逻辑在业务层（插入前按 concept_id+source_id+timestamp_sec 查重）。 */
    suspend fun addSource(cs: WebenConceptSource)
    suspend fun listSources(conceptId: String): List<WebenConceptSource>
}

// =============================================================================
// WebenConceptService
// =============================================================================

object WebenConceptService {
    private var _repo: WebenConceptRepo? = null
    val repo: WebenConceptRepo
        get() = _repo ?: error("WebenConceptService 未初始化，请先调用 initialize()")
    fun initialize(repo: WebenConceptRepo) { _repo = repo }
}

// =============================================================================
// WebenConceptDb —— weben_concept / weben_concept_alias / weben_concept_source 表的 JDBC 实现
// =============================================================================

class WebenConceptDb(private val db: Database) : WebenConceptRepo {

    suspend fun initialize() = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                // weben_concept
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS weben_concept (
                        id               TEXT    PRIMARY KEY,
                        canonical_name   TEXT    NOT NULL UNIQUE,
                        concept_type     TEXT    NOT NULL,
                        brief_definition TEXT,
                        metadata_json    TEXT    NOT NULL DEFAULT '{}',
                        confidence       REAL    NOT NULL DEFAULT 1.0,
                        mastery          REAL    NOT NULL DEFAULT 0.0,
                        first_seen_at    INTEGER NOT NULL,
                        last_seen_at     INTEGER NOT NULL,
                        created_at       INTEGER NOT NULL,
                        updated_at       INTEGER NOT NULL
                    )
                """.trimIndent())
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_wc_type_mastery ON weben_concept(concept_type, mastery)"
                )
                // weben_concept_alias
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS weben_concept_alias (
                        id           INTEGER PRIMARY KEY AUTOINCREMENT,
                        concept_id   TEXT    NOT NULL,
                        alias        TEXT    NOT NULL,
                        alias_source TEXT,
                        UNIQUE (concept_id, alias)
                    )
                """.trimIndent())
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_wca_alias ON weben_concept_alias(alias)"
                )
                // weben_concept_source
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS weben_concept_source (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        concept_id    TEXT    NOT NULL,
                        source_id     TEXT    NOT NULL,
                        timestamp_sec REAL,
                        excerpt       TEXT
                    )
                """.trimIndent())
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_wcs_concept_source ON weben_concept_source(concept_id, source_id)"
                )
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_wcs_source ON weben_concept_source(source_id)"
                )
            }
        }
    }

    override suspend fun create(concept: WebenConcept) = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("""
                INSERT INTO weben_concept
                    (id, canonical_name, concept_type, brief_definition, metadata_json,
                     confidence, mastery, first_seen_at, last_seen_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO NOTHING
            """.trimIndent()).use { ps ->
                ps.setString(1, concept.id)
                ps.setString(2, concept.canonicalName)
                ps.setString(3, concept.conceptType)
                ps.setStringOrNull(4, concept.briefDefinition)
                ps.setString(5, concept.metadataJson)
                ps.setDouble(6, concept.confidence)
                ps.setDouble(7, concept.mastery)
                ps.setLong(8, concept.firstSeenAt)
                ps.setLong(9, concept.lastSeenAt)
                ps.setLong(10, concept.createdAt)
                ps.setLong(11, concept.updatedAt)
                ps.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun getById(id: String): WebenConcept? = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("SELECT * FROM weben_concept WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toConcept() else null }
            }
        }
    }

    override suspend fun getByCanonicalName(canonicalName: String): WebenConcept? = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("SELECT * FROM weben_concept WHERE canonical_name = ?").use { ps ->
                ps.setString(1, canonicalName)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toConcept() else null }
            }
        }
    }

    override suspend fun listAll(conceptType: String?, limit: Int, offset: Int): List<WebenConcept> =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                val sql = if (conceptType != null)
                    "SELECT * FROM weben_concept WHERE concept_type = ? ORDER BY mastery ASC LIMIT ? OFFSET ?"
                else
                    "SELECT * FROM weben_concept ORDER BY mastery ASC LIMIT ? OFFSET ?"
                conn.prepareStatement(sql).use { ps ->
                    if (conceptType != null) {
                        ps.setString(1, conceptType); ps.setInt(2, limit); ps.setInt(3, offset)
                    } else {
                        ps.setInt(1, limit); ps.setInt(2, offset)
                    }
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toConcept()) } }
                }
            }
        }

    override suspend fun update(concept: WebenConcept) = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("""
                UPDATE weben_concept
                SET concept_type     = ?,
                    brief_definition = ?,
                    metadata_json    = ?,
                    confidence       = ?,
                    last_seen_at     = ?,
                    updated_at       = ?
                WHERE id = ?
            """.trimIndent()).use { ps ->
                ps.setString(1, concept.conceptType)
                ps.setStringOrNull(2, concept.briefDefinition)
                ps.setString(3, concept.metadataJson)
                ps.setDouble(4, concept.confidence)
                ps.setLong(5, concept.lastSeenAt)
                ps.setLong(6, concept.updatedAt)
                ps.setString(7, concept.id)
                ps.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun updateMastery(id: String, mastery: Double) = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("UPDATE weben_concept SET mastery = ? WHERE id = ?").use { ps ->
                ps.setDouble(1, mastery)
                ps.setString(2, id)
                ps.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun addAlias(alias: WebenConceptAlias) = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("""
                INSERT INTO weben_concept_alias (concept_id, alias, alias_source)
                VALUES (?, ?, ?)
                ON CONFLICT(concept_id, alias) DO NOTHING
            """.trimIndent()).use { ps ->
                ps.setString(1, alias.conceptId)
                ps.setString(2, alias.alias)
                ps.setStringOrNull(3, alias.aliasSource)
                ps.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun listAliases(conceptId: String): List<WebenConceptAlias> =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM weben_concept_alias WHERE concept_id = ? ORDER BY id ASC"
                ).use { ps ->
                    ps.setString(1, conceptId)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toAlias()) } }
                }
            }
        }

    override suspend fun deleteAlias(id: Long) = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("DELETE FROM weben_concept_alias WHERE id = ?").use { ps ->
                ps.setLong(1, id)
                ps.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun addSource(cs: WebenConceptSource) = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("""
                INSERT INTO weben_concept_source (concept_id, source_id, timestamp_sec, excerpt)
                VALUES (?, ?, ?, ?)
            """.trimIndent()).use { ps ->
                ps.setString(1, cs.conceptId)
                ps.setString(2, cs.sourceId)
                if (cs.timestampSec != null) ps.setDouble(3, cs.timestampSec) else ps.setNull(3, Types.REAL)
                ps.setStringOrNull(4, cs.excerpt)
                ps.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun listSources(conceptId: String): List<WebenConceptSource> =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM weben_concept_source WHERE concept_id = ? ORDER BY timestamp_sec ASC"
                ).use { ps ->
                    ps.setString(1, conceptId)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toConceptSource()) } }
                }
            }
        }

    private fun ResultSet.toConcept() = WebenConcept(
        id              = getString("id"),
        canonicalName   = getString("canonical_name"),
        conceptType     = getString("concept_type"),
        briefDefinition = getString("brief_definition"),
        metadataJson    = getString("metadata_json"),
        confidence      = getDouble("confidence"),
        mastery         = getDouble("mastery"),
        firstSeenAt     = getLong("first_seen_at"),
        lastSeenAt      = getLong("last_seen_at"),
        createdAt       = getLong("created_at"),
        updatedAt       = getLong("updated_at"),
    )

    private fun ResultSet.toAlias() = WebenConceptAlias(
        id          = getLong("id"),
        conceptId   = getString("concept_id"),
        alias       = getString("alias"),
        aliasSource = getString("alias_source"),
    )

    private fun ResultSet.toConceptSource() = WebenConceptSource(
        id           = getLong("id"),
        conceptId    = getString("concept_id"),
        sourceId     = getString("source_id"),
        timestampSec = getDouble("timestamp_sec").takeIf { !wasNull() },
        excerpt      = getString("excerpt"),
    )
}

private fun java.sql.PreparedStatement.setStringOrNull(idx: Int, v: String?) {
    if (v != null) setString(idx, v) else setNull(idx, Types.VARCHAR)
}
