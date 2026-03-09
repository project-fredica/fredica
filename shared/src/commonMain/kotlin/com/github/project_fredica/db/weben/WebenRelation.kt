package com.github.project_fredica.db.weben

// =============================================================================
// WebenRelation —— 概念关系边
// =============================================================================
//
// 管理两张表：
//   weben_relation        — 概念自关联的 M:N 关联表（主体 → 谓语 → 客体）
//   weben_relation_source — 关系-来源关联（M:N）
// =============================================================================

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.ktorm.database.Database
import java.sql.ResultSet
import java.sql.Types

@Serializable
data class WebenRelation(
    /** UUID。 */
    val id: String,
    /** 主体概念（weben_concept.id）。 */
    @SerialName("subject_id") val subjectId: String,
    /** 关系谓语：'包含'|'依赖'|'用于'|'对比'|'是...的实例'|'实现'|'扩展'。 */
    val predicate: String,
    /** 客体概念（weben_concept.id）。 */
    @SerialName("object_id") val objectId: String,
    /** 关系置信度（0-1），多来源支撑时可累积更新。 */
    val confidence: Double = 1.0,
    /** 来源标记：false=AI 推导，true=用户手动添加。 */
    @SerialName("is_manual") val isManual: Boolean = false,
    /** 记录创建时间，Unix 秒。 */
    @SerialName("created_at") val createdAt: Long,
    /** 最后更新时间（confidence 被新来源更新时），Unix 秒。 */
    @SerialName("updated_at") val updatedAt: Long,
)

@Serializable
data class WebenRelationSource(
    /** 自增主键。 */
    val id: Long = 0,
    /** 所属关系（weben_relation.id）。 */
    @SerialName("relation_id") val relationId: String,
    /** 来源（weben_source.id）。 */
    @SerialName("source_id") val sourceId: String,
    /** 该关系被提及的视频时间点（秒），非视频来源为 null。 */
    @SerialName("timestamp_sec") val timestampSec: Double? = null,
    /** 来源原文摘录。 */
    val excerpt: String? = null,
)

// =============================================================================
// WebenRelationRepo
// =============================================================================

interface WebenRelationRepo {
    /**
     * 按幂等写入关系。
     * UNIQUE(subject_id, predicate, object_id) 冲突时更新 confidence 和 updated_at。
     */
    suspend fun upsert(relation: WebenRelation)
    suspend fun getById(id: String): WebenRelation?
    /** 正向遍历：以 subjectId 为主体出发的所有边。 */
    suspend fun listBySubject(subjectId: String): List<WebenRelation>
    /** 反向遍历：所有指向 objectId 的边。 */
    suspend fun listByObject(objectId: String): List<WebenRelation>
    /** 双向查询：概念详情页/图谱展示用（出边 + 入边合并，调用方去重）。 */
    suspend fun listByConcept(conceptId: String): List<WebenRelation>
    suspend fun deleteById(id: String)
    // — 来源关联 —
    /** 插入关系-来源关联；去重逻辑在业务层。 */
    suspend fun addSource(rs: WebenRelationSource)
    suspend fun listSources(relationId: String): List<WebenRelationSource>
}

// =============================================================================
// WebenRelationService
// =============================================================================

object WebenRelationService {
    private var _repo: WebenRelationRepo? = null
    val repo: WebenRelationRepo
        get() = _repo ?: error("WebenRelationService 未初始化，请先调用 initialize()")
    fun initialize(repo: WebenRelationRepo) { _repo = repo }
}

// =============================================================================
// WebenRelationDb —— weben_relation / weben_relation_source 表的 JDBC 实现
// =============================================================================

class WebenRelationDb(private val db: Database) : WebenRelationRepo {

    suspend fun initialize() = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                // weben_relation
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS weben_relation (
                        id          TEXT    PRIMARY KEY,
                        subject_id  TEXT    NOT NULL,
                        predicate   TEXT    NOT NULL,
                        object_id   TEXT    NOT NULL,
                        confidence  REAL    NOT NULL DEFAULT 1.0,
                        is_manual   INTEGER NOT NULL DEFAULT 0,
                        created_at  INTEGER NOT NULL,
                        updated_at  INTEGER NOT NULL,
                        UNIQUE (subject_id, predicate, object_id)
                    )
                """.trimIndent())
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_wr_subject ON weben_relation(subject_id)"
                )
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_wr_object ON weben_relation(object_id)"
                )
                // weben_relation_source
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS weben_relation_source (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        relation_id   TEXT    NOT NULL,
                        source_id     TEXT    NOT NULL,
                        timestamp_sec REAL,
                        excerpt       TEXT
                    )
                """.trimIndent())
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_wrs_relation ON weben_relation_source(relation_id)"
                )
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_wrs_source ON weben_relation_source(source_id)"
                )
            }
        }
    }

    override suspend fun upsert(relation: WebenRelation) = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("""
                INSERT INTO weben_relation
                    (id, subject_id, predicate, object_id, confidence, is_manual, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(subject_id, predicate, object_id)
                    DO UPDATE SET confidence  = excluded.confidence,
                                  updated_at = excluded.updated_at
            """.trimIndent()).use { ps ->
                ps.setString(1, relation.id)
                ps.setString(2, relation.subjectId)
                ps.setString(3, relation.predicate)
                ps.setString(4, relation.objectId)
                ps.setDouble(5, relation.confidence)
                ps.setInt(6, if (relation.isManual) 1 else 0)
                ps.setLong(7, relation.createdAt)
                ps.setLong(8, relation.updatedAt)
                ps.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun getById(id: String): WebenRelation? = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("SELECT * FROM weben_relation WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toRelation() else null }
            }
        }
    }

    override suspend fun listBySubject(subjectId: String): List<WebenRelation> =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM weben_relation WHERE subject_id = ? ORDER BY created_at DESC"
                ).use { ps ->
                    ps.setString(1, subjectId)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toRelation()) } }
                }
            }
        }

    override suspend fun listByObject(objectId: String): List<WebenRelation> =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM weben_relation WHERE object_id = ? ORDER BY created_at DESC"
                ).use { ps ->
                    ps.setString(1, objectId)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toRelation()) } }
                }
            }
        }

    override suspend fun listByConcept(conceptId: String): List<WebenRelation> =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM weben_relation WHERE subject_id = ? OR object_id = ? ORDER BY created_at DESC"
                ).use { ps ->
                    ps.setString(1, conceptId)
                    ps.setString(2, conceptId)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toRelation()) } }
                }
            }
        }

    override suspend fun deleteById(id: String) = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("DELETE FROM weben_relation WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun addSource(rs: WebenRelationSource) = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("""
                INSERT INTO weben_relation_source (relation_id, source_id, timestamp_sec, excerpt)
                VALUES (?, ?, ?, ?)
            """.trimIndent()).use { ps ->
                ps.setString(1, rs.relationId)
                ps.setString(2, rs.sourceId)
                if (rs.timestampSec != null) ps.setDouble(3, rs.timestampSec) else ps.setNull(3, Types.REAL)
                ps.setStringOrNull(4, rs.excerpt)
                ps.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun listSources(relationId: String): List<WebenRelationSource> =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM weben_relation_source WHERE relation_id = ? ORDER BY id ASC"
                ).use { ps ->
                    ps.setString(1, relationId)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toRelationSource()) } }
                }
            }
        }

    private fun ResultSet.toRelation() = WebenRelation(
        id         = getString("id"),
        subjectId  = getString("subject_id"),
        predicate  = getString("predicate"),
        objectId   = getString("object_id"),
        confidence = getDouble("confidence"),
        isManual   = getInt("is_manual") != 0,
        createdAt  = getLong("created_at"),
        updatedAt  = getLong("updated_at"),
    )

    private fun ResultSet.toRelationSource() = WebenRelationSource(
        id           = getLong("id"),
        relationId   = getString("relation_id"),
        sourceId     = getString("source_id"),
        timestampSec = getDouble("timestamp_sec").takeIf { !wasNull() },
        excerpt      = getString("excerpt"),
    )
}

private fun java.sql.PreparedStatement.setStringOrNull(idx: Int, v: String?) {
    if (v != null) setString(idx, v) else setNull(idx, Types.VARCHAR)
}
