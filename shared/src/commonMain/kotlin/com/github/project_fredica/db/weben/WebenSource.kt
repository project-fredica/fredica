package com.github.project_fredica.db.weben

// =============================================================================
// WebenSource —— 视频/文档来源
// =============================================================================
//
// 一条 WebenSource 代表一个可以产出概念的知识来源（B 站视频、本地文件或文章）。
// 与素材库的关系：materialId 可选关联 material.id；
// 若用户直接提交外部 URL，则 materialId 为 null。
// =============================================================================

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.ktorm.database.Database
import java.sql.ResultSet
import java.sql.Types

@Serializable
data class WebenSource(
    /** UUID，来源唯一标识。 */
    val id: String,
    /** 关联素材库 material.id，外部直接导入时为 null。 */
    @SerialName("material_id") val materialId: String? = null,
    /** 完整资源地址：视频页面 URL 或本地文件绝对路径。 */
    val url: String,
    /** 来源标题（视频标题或文章标题）。 */
    val title: String,
    /** 来源类型：'bilibili_video' | 'local_file' | 'web_article'。 */
    @SerialName("source_type") val sourceType: String,
    /** Bilibili 视频 BV 号，bilibili_video 专属，其余为 null。 */
    val bvid: String? = null,
    /** 视频总时长（秒），非视频来源为 null。 */
    @SerialName("duration_sec") val durationSec: Double? = null,
    /** 来源质量分（0-1），用于图谱置信度加权，默认中等。 */
    @SerialName("quality_score") val qualityScore: Double = 0.5,
    /** 分析流水线状态：'pending' | 'analyzing' | 'completed' | 'failed'。 */
    @SerialName("analysis_status") val analysisStatus: String = "pending",
    /** 记录创建时间，Unix 秒。 */
    @SerialName("created_at") val createdAt: Long,
)

// =============================================================================
// WebenSourceRepo
// =============================================================================

interface WebenSourceRepo {
    suspend fun create(source: WebenSource)
    suspend fun getById(id: String): WebenSource?
    suspend fun listAll(materialId: String? = null): List<WebenSource>
    suspend fun updateAnalysisStatus(id: String, status: String)
}

// =============================================================================
// WebenSourceService
// =============================================================================

object WebenSourceService {
    private var _repo: WebenSourceRepo? = null
    val repo: WebenSourceRepo
        get() = _repo ?: error("WebenSourceService 未初始化，请先调用 initialize()")
    fun initialize(repo: WebenSourceRepo) { _repo = repo }
}

// =============================================================================
// WebenSourceDb —— weben_source 表的 JDBC 实现
// =============================================================================

class WebenSourceDb(private val db: Database) : WebenSourceRepo {

    suspend fun initialize() = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS weben_source (
                        id              TEXT    PRIMARY KEY,
                        material_id     TEXT,
                        url             TEXT    NOT NULL,
                        title           TEXT    NOT NULL,
                        source_type     TEXT    NOT NULL,
                        bvid            TEXT,
                        duration_sec    REAL,
                        quality_score   REAL    NOT NULL DEFAULT 0.5,
                        analysis_status TEXT    NOT NULL DEFAULT 'pending',
                        created_at      INTEGER NOT NULL
                    )
                """.trimIndent())
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_ws_material ON weben_source(material_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_ws_bvid ON weben_source(bvid)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_ws_analysis_status ON weben_source(analysis_status)")
            }
        }
    }

    override suspend fun create(source: WebenSource) = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("""
                INSERT INTO weben_source
                    (id, material_id, url, title, source_type, bvid, duration_sec, quality_score, analysis_status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO NOTHING
            """.trimIndent()).use { ps ->
                ps.setString(1, source.id)
                ps.setStringOrNull(2, source.materialId)
                ps.setString(3, source.url)
                ps.setString(4, source.title)
                ps.setString(5, source.sourceType)
                ps.setStringOrNull(6, source.bvid)
                if (source.durationSec != null) ps.setDouble(7, source.durationSec)
                else ps.setNull(7, Types.REAL)
                ps.setDouble(8, source.qualityScore)
                ps.setString(9, source.analysisStatus)
                ps.setLong(10, source.createdAt)
                ps.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun getById(id: String): WebenSource? = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("SELECT * FROM weben_source WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toSource() else null }
            }
        }
    }

    override suspend fun listAll(materialId: String?): List<WebenSource> = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            val sql = if (materialId != null)
                "SELECT * FROM weben_source WHERE material_id = ? ORDER BY created_at DESC"
            else
                "SELECT * FROM weben_source ORDER BY created_at DESC"
            conn.prepareStatement(sql).use { ps ->
                if (materialId != null) ps.setString(1, materialId)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toSource()) } }
            }
        }
    }

    override suspend fun updateAnalysisStatus(id: String, status: String) = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("UPDATE weben_source SET analysis_status = ? WHERE id = ?").use { ps ->
                ps.setString(1, status)
                ps.setString(2, id)
                ps.executeUpdate()
            }
        }
        Unit
    }

    private fun ResultSet.toSource() = WebenSource(
        id             = getString("id"),
        materialId     = getString("material_id"),
        url            = getString("url"),
        title          = getString("title"),
        sourceType     = getString("source_type"),
        bvid           = getString("bvid"),
        durationSec    = getDouble("duration_sec").takeIf { !wasNull() },
        qualityScore   = getDouble("quality_score"),
        analysisStatus = getString("analysis_status"),
        createdAt      = getLong("created_at"),
    )
}

private fun java.sql.PreparedStatement.setStringOrNull(idx: Int, v: String?) {
    if (v != null) setString(idx, v) else setNull(idx, Types.VARCHAR)
}
