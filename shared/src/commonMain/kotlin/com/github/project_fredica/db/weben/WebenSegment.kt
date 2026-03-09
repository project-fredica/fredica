package com.github.project_fredica.db.weben

// =============================================================================
// WebenSegment —— 视频时间段
// =============================================================================
//
// 管理两张表：
//   weben_segment         — 视频按时间切块的摘要段（播放器上下文保持的数据基础）
//   weben_segment_concept — 段-概念多对多（M:N 关联表）
// =============================================================================

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.ktorm.database.Database
import java.sql.ResultSet

@Serializable
data class WebenSegment(
    /** UUID。 */
    val id: String,
    /** 所属视频来源（weben_source.id）。 */
    @SerialName("source_id") val sourceId: String,
    /** 段序号（0-based），同一来源内按 seq 排序即为时间顺序。 */
    val seq: Int,
    /** 本段起始时间（秒）。 */
    @SerialName("start_sec") val startSec: Double,
    /** 本段结束时间（秒）。 */
    @SerialName("end_sec") val endSec: Double,
    /** AI 生成的分段摘要，用于预览窗口要点列表；null 表示尚未 LLM 分析。 */
    val summary: String? = null,
    /** 一句话标题，用于播放器顶部横幅；null 表示尚未 LLM 分析。 */
    val headline: String? = null,
    /** 切块写入时间，Unix 秒。 */
    @SerialName("created_at") val createdAt: Long,
)

@Serializable
data class WebenSegmentConcept(
    /** 所属段（weben_segment.id）。 */
    @SerialName("segment_id") val segmentId: String,
    /** 关联概念（weben_concept.id）。 */
    @SerialName("concept_id") val conceptId: String,
    /** 是否为该段的核心概念（true=是），用于播放器进度条高亮和顶部横幅展示。 */
    @SerialName("is_primary") val isPrimary: Boolean = false,
)

// =============================================================================
// WebenSegmentRepo
// =============================================================================

interface WebenSegmentRepo {
    /**
     * 幂等写入段落。
     * UNIQUE(source_id, seq) 冲突时更新 summary 和 headline（LLM 分析完成后回填）。
     */
    suspend fun upsert(segment: WebenSegment)
    suspend fun getById(id: String): WebenSegment?
    /** 播放器时间轴数据：按 seq 升序返回某来源的全部段落。 */
    suspend fun listBySource(sourceId: String): List<WebenSegment>
    // — 段-概念关联 —
    /** 插入段-概念关联；主键 (segment_id, concept_id) 冲突时忽略。 */
    suspend fun linkConcept(sc: WebenSegmentConcept)
    /** 查某段内的全部关联概念（含 is_primary），用于进度条标记。 */
    suspend fun listConceptsBySegment(segmentId: String): List<WebenSegmentConcept>
    /** 反向查：某概念出现在哪些段，用于概念详情页的来源时间线。 */
    suspend fun listSegmentsByConcept(conceptId: String): List<WebenSegmentConcept>
}

// =============================================================================
// WebenSegmentService
// =============================================================================

object WebenSegmentService {
    private var _repo: WebenSegmentRepo? = null
    val repo: WebenSegmentRepo
        get() = _repo ?: error("WebenSegmentService 未初始化，请先调用 initialize()")
    fun initialize(repo: WebenSegmentRepo) { _repo = repo }
}

// =============================================================================
// WebenSegmentDb —— weben_segment / weben_segment_concept 表的 JDBC 实现
// =============================================================================

class WebenSegmentDb(private val db: Database) : WebenSegmentRepo {

    suspend fun initialize() = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                // weben_segment
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS weben_segment (
                        id          TEXT    PRIMARY KEY,
                        source_id   TEXT    NOT NULL,
                        seq         INTEGER NOT NULL,
                        start_sec   REAL    NOT NULL,
                        end_sec     REAL    NOT NULL,
                        summary     TEXT,
                        headline    TEXT,
                        created_at  INTEGER NOT NULL,
                        UNIQUE (source_id, seq)
                    )
                """.trimIndent())
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_wseg_source ON weben_segment(source_id)"
                )
                // weben_segment_concept
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS weben_segment_concept (
                        segment_id  TEXT    NOT NULL,
                        concept_id  TEXT    NOT NULL,
                        is_primary  INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (segment_id, concept_id)
                    )
                """.trimIndent())
                // PRIMARY KEY (segment_id, concept_id) 已覆盖按 segment_id 正向查询；
                // 添加 concept_id 反向索引，支持"概念出现在哪些段"。
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_wsc_concept ON weben_segment_concept(concept_id)"
                )
            }
        }
    }

    override suspend fun upsert(segment: WebenSegment) = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("""
                INSERT INTO weben_segment
                    (id, source_id, seq, start_sec, end_sec, summary, headline, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(source_id, seq)
                    DO UPDATE SET summary  = excluded.summary,
                                  headline = excluded.headline
            """.trimIndent()).use { ps ->
                ps.setString(1, segment.id)
                ps.setString(2, segment.sourceId)
                ps.setInt(3, segment.seq)
                ps.setDouble(4, segment.startSec)
                ps.setDouble(5, segment.endSec)
                ps.setStringOrNull(6, segment.summary)
                ps.setStringOrNull(7, segment.headline)
                ps.setLong(8, segment.createdAt)
                ps.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun getById(id: String): WebenSegment? = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("SELECT * FROM weben_segment WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toSegment() else null }
            }
        }
    }

    override suspend fun listBySource(sourceId: String): List<WebenSegment> =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM weben_segment WHERE source_id = ? ORDER BY seq ASC"
                ).use { ps ->
                    ps.setString(1, sourceId)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toSegment()) } }
                }
            }
        }

    override suspend fun linkConcept(sc: WebenSegmentConcept) = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("""
                INSERT INTO weben_segment_concept (segment_id, concept_id, is_primary)
                VALUES (?, ?, ?)
                ON CONFLICT(segment_id, concept_id) DO NOTHING
            """.trimIndent()).use { ps ->
                ps.setString(1, sc.segmentId)
                ps.setString(2, sc.conceptId)
                ps.setInt(3, if (sc.isPrimary) 1 else 0)
                ps.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun listConceptsBySegment(segmentId: String): List<WebenSegmentConcept> =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM weben_segment_concept WHERE segment_id = ?"
                ).use { ps ->
                    ps.setString(1, segmentId)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toSegmentConcept()) } }
                }
            }
        }

    override suspend fun listSegmentsByConcept(conceptId: String): List<WebenSegmentConcept> =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM weben_segment_concept WHERE concept_id = ?"
                ).use { ps ->
                    ps.setString(1, conceptId)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toSegmentConcept()) } }
                }
            }
        }

    private fun ResultSet.toSegment() = WebenSegment(
        id        = getString("id"),
        sourceId  = getString("source_id"),
        seq       = getInt("seq"),
        startSec  = getDouble("start_sec"),
        endSec    = getDouble("end_sec"),
        summary   = getString("summary"),
        headline  = getString("headline"),
        createdAt = getLong("created_at"),
    )

    private fun ResultSet.toSegmentConcept() = WebenSegmentConcept(
        segmentId = getString("segment_id"),
        conceptId = getString("concept_id"),
        isPrimary = getInt("is_primary") != 0,
    )
}

private fun java.sql.PreparedStatement.setStringOrNull(idx: Int, v: String?) {
    if (v != null) setString(idx, v) else setNull(idx, java.sql.Types.VARCHAR)
}
