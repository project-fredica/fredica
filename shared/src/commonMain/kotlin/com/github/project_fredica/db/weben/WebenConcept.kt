package com.github.project_fredica.db.weben

// =============================================================================
// WebenConcept —— 概念节点
// =============================================================================
//
// 管理三张表：
//   weben_concept        — 概念节点
//   weben_concept_alias  — 概念别名（用于去重合并时的模糊匹配）
//   weben_concept_source — 概念-来源时间点关联（M:N）
// =============================================================================

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.error
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.ktorm.database.Database
import java.sql.ResultSet
import java.sql.Types
import kotlin.collections.map
import kotlin.collections.plus

@Serializable
data class WebenConcept(
    /** UUID，概念唯一标识。 */
    val id: String,
    /** 所属素材 ID（素材作用域）；null 表示全局/遗留数据。概念在同一素材内按 canonical_name 去重。 */
    @SerialName("material_id") val materialId: String? = null,
    /** 规范化名称（去标点小写后的去重键，同时作为展示名）。同一素材内唯一。 */
    @SerialName("canonical_name") val canonicalName: String,
    /** 概念类型：开放文本字段，由 LLM / 用户自由定义；程序侧只提供 examples，不维护允许列表。 */
    @SerialName("concept_type") val conceptType: String,
    /** AI 生成的简短定义，用户可手动修正；null 表示尚未生成。 */
    @SerialName("brief_definition") val briefDefinition: String? = null,
    /** 类型特定结构化元数据（公式变量说明、器件厂商参数等）。 */
    @SerialName("metadata_json") val metadataJson: String = "{}",
    /** AI 提取置信度（0-1），用户手动添加的概念固定为 1.0。 */
    val confidence: Double = 1.0,
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
    suspend fun getByCanonicalName(canonicalName: String, materialId: String? = null): WebenConcept?
    suspend fun listDistinctConceptTypes(): List<String>
    /** 瀑布流分页：可按 conceptType / sourceId / materialId 过滤，按 canonical_name 排序。
     *  materialId：按 weben_concept.material_id 直接过滤（概念存储时即绑定素材）。
     */
    suspend fun listAll(conceptType: String? = null, sourceId: String? = null, materialId: String? = null, limit: Int = 50, offset: Int = 0): List<WebenConcept>
    /** 返回满足过滤条件的概念总数。 */
    suspend fun count(conceptType: String? = null, sourceId: String? = null, materialId: String? = null): Int
    /** 更新概念定义与元数据（用户手动修正），同步更新 updated_at。 */
    suspend fun update(concept: WebenConcept)
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
                // weben_concept (新 schema：material_id 列 + UNIQUE(material_id, canonical_name))
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS weben_concept (
                        id               TEXT    PRIMARY KEY,
                        material_id      TEXT,
                        canonical_name   TEXT    NOT NULL,
                        concept_type     TEXT    NOT NULL,
                        brief_definition TEXT,
                        metadata_json    TEXT    NOT NULL DEFAULT '{}',
                        confidence       REAL    NOT NULL DEFAULT 1.0,
                        first_seen_at    INTEGER NOT NULL,
                        last_seen_at     INTEGER NOT NULL,
                        created_at       INTEGER NOT NULL,
                        updated_at       INTEGER NOT NULL,
                        UNIQUE (material_id, canonical_name)
                    )
                """.trimIndent())

                // 迁移旧 schema（canonical_name UNIQUE 全局，无 material_id 列）→ 新 schema
                val hasMaterialId = conn.prepareStatement("PRAGMA table_info(weben_concept)").use { ps ->
                    ps.executeQuery().use { rs ->
                        var found = false
                        while (rs.next()) { if (rs.getString("name") == "material_id") { found = true; break } }
                        found
                    }
                }
                if (!hasMaterialId) {
                    stmt.execute("""
                        CREATE TABLE weben_concept_v2 (
                            id               TEXT    PRIMARY KEY,
                            material_id      TEXT,
                            canonical_name   TEXT    NOT NULL,
                            concept_type     TEXT    NOT NULL,
                            brief_definition TEXT,
                            metadata_json    TEXT    NOT NULL DEFAULT '{}',
                            confidence       REAL    NOT NULL DEFAULT 1.0,
                            first_seen_at    INTEGER NOT NULL,
                            last_seen_at     INTEGER NOT NULL,
                            created_at       INTEGER NOT NULL,
                            updated_at       INTEGER NOT NULL,
                            UNIQUE (material_id, canonical_name)
                        )
                    """.trimIndent())
                    stmt.execute("""
                        INSERT INTO weben_concept_v2
                            (id, material_id, canonical_name, concept_type, brief_definition,
                             metadata_json, confidence, first_seen_at, last_seen_at, created_at, updated_at)
                        SELECT id, NULL, canonical_name, concept_type, brief_definition,
                               metadata_json, confidence, first_seen_at, last_seen_at, created_at, updated_at
                        FROM weben_concept
                    """.trimIndent())
                    stmt.execute("DROP TABLE weben_concept")
                    stmt.execute("ALTER TABLE weben_concept_v2 RENAME TO weben_concept")
                }

                stmt.execute("CREATE INDEX IF NOT EXISTS idx_wc_type ON weben_concept(concept_type)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_wc_material ON weben_concept(material_id)")
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
                INSERT OR IGNORE INTO weben_concept
                    (id, material_id, canonical_name, concept_type, brief_definition, metadata_json,
                     confidence, first_seen_at, last_seen_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { ps ->
                ps.setString(1, concept.id)
                ps.setStringOrNull(2, concept.materialId)
                ps.setString(3, concept.canonicalName)
                ps.setString(4, concept.conceptType)
                ps.setStringOrNull(5, concept.briefDefinition)
                ps.setString(6, concept.metadataJson)
                ps.setDouble(7, concept.confidence)
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

    override suspend fun getByCanonicalName(canonicalName: String, materialId: String?): WebenConcept? = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            val sql = if (materialId != null)
                "SELECT * FROM weben_concept WHERE canonical_name = ? AND material_id = ?"
            else
                "SELECT * FROM weben_concept WHERE canonical_name = ? AND material_id IS NULL"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, canonicalName)
                if (materialId != null) ps.setString(2, materialId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toConcept() else null }
            }
        }
    }

    override suspend fun listDistinctConceptTypes(): List<String> = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                "SELECT DISTINCT concept_type FROM weben_concept WHERE TRIM(concept_type) <> ''"
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            rs.getString(1).split(",")
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .forEach { add(it) }
                        }
                    }
                }
            }
        }
    }

    override suspend fun listAll(conceptType: String?, sourceId: String?, materialId: String?, limit: Int, offset: Int): List<WebenConcept> =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                val conditions = buildList {
                    if (sourceId != null) add("c.id IN (SELECT concept_id FROM weben_concept_source WHERE source_id = ?)")
                    if (materialId != null) add("c.material_id = ?")
                    if (conceptType != null) add("INSTR(','||c.concept_type||',', ','||?||',') > 0")
                }
                val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
                val sql = "SELECT c.* FROM weben_concept c $where ORDER BY c.canonical_name ASC LIMIT ? OFFSET ?"
                conn.prepareStatement(sql).use { ps ->
                    var idx = 1
                    if (sourceId != null) ps.setString(idx++, sourceId)
                    if (materialId != null) ps.setString(idx++, materialId)
                    if (conceptType != null) ps.setString(idx++, conceptType)
                    ps.setInt(idx++, limit); ps.setInt(idx, offset)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toConcept()) } }
                }
            }
        }

    override suspend fun count(conceptType: String?, sourceId: String?, materialId: String?): Int =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                val conditions = buildList {
                    if (sourceId != null) add("id IN (SELECT concept_id FROM weben_concept_source WHERE source_id = ?)")
                    if (materialId != null) add("material_id = ?")
                    if (conceptType != null) add("INSTR(','||concept_type||',', ','||?||',') > 0")
                }
                val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
                conn.prepareStatement("SELECT COUNT(*) FROM weben_concept $where").use { ps ->
                    var idx = 1
                    if (sourceId != null) ps.setString(idx++, sourceId)
                    if (materialId != null) ps.setString(idx++, materialId)
                    if (conceptType != null) ps.setString(idx, conceptType)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
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
        materialId      = getString("material_id"),
        canonicalName   = getString("canonical_name"),
        conceptType     = getString("concept_type"),
        briefDefinition = getString("brief_definition"),
        metadataJson    = getString("metadata_json"),
        confidence      = getDouble("confidence"),
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
