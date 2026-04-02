package com.github.project_fredica.db.weben

// =============================================================================
// WebenSource —— 视频/文档来源
// =============================================================================
//
// 一条 WebenSource 代表一个可以产出概念的知识来源（B 站视频、本地文件或文章）。
// 与素材库的关系：materialId 可选关联 material.id；
// 若用户直接提交外部 URL，则 materialId 为 null。
// =============================================================================

import com.github.project_fredica.db.TaskService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.ktorm.database.Database
import java.sql.ResultSet
import java.sql.Types

// =============================================================================
// WebenSourceDb —— weben_source 表的 JDBC 实现
// =============================================================================

private val dbLogger = com.github.project_fredica.apputil.createLogger { "WebenSourceDb" }

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
    /** 关联的工作流运行实例 ID，用于状态对账。 */
    @SerialName("workflow_run_id") val workflowRunId: String? = null,
    /**
     * 整体分析进度（0–100）。
     * 由 [WebenSourceService.syncProgressFromGraph] 从工作流任务图动态计算
     * （所有任务进度的加权平均），每次执行器汇报任务进度时同步更新，
     * 无需在各 Executor 中硬编码权重分配。
     * 已完成的任务贡献 100%，进行中的任务贡献其实际 progress 值。
     */
    val progress: Int = 0,
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
    suspend fun listPaged(materialId: String? = null, limit: Int = 20, offset: Int = 0): List<WebenSource>
    suspend fun count(materialId: String? = null): Int
    suspend fun updateAnalysisStatus(id: String, status: String)
    /** 按主键更新进度（0–100）。 */
    suspend fun updateProgress(id: String, progress: Int)
    /** 按 workflow_run_id 批量更新进度，用于 [WebenSourceService.syncProgressFromGraph]。 */
    suspend fun updateProgressByWorkflowRunId(workflowRunId: String, progress: Int)
}

// =============================================================================
// WebenSourceService
// =============================================================================

object WebenSourceService {
    private var _repo: WebenSourceRepo? = null
    val repo: WebenSourceRepo
        get() = _repo ?: error("WebenSourceService 未初始化，请先调用 initialize()")
    fun initialize(repo: WebenSourceRepo) { _repo = repo }

    /**
     * 从工作流任务图动态计算来源整体进度，并同步写入 [WebenSource.progress]。
     *
     * ## 算法
     * 所有任务进度的简单平均值，其中：
     * - `completed` 任务贡献 **100**（无论 task.progress 字段的实际值）
     * - 其他状态（running / pending / claimed）贡献 `task.progress`（0–100）
     *
     * 示例：FETCH_SUBTITLE(completed=100) + WEBEN_CONCEPT_EXTRACT(running=40%)
     * → source.progress = (100 + 40) / 2 = **70**
     *
     * ## 调用时机
     * 在各 Executor 每次调用 `TaskService.repo.updateProgress()` 后触发，
     * 由此摆脱对 Executor 数量或权重的硬编码依赖。
     * 进度写失败不阻断主业务流程，异常被静默吞掉。
     */
    suspend fun syncProgressFromGraph(workflowRunId: String) {
        val tasks = runCatching { TaskService.repo.listByWorkflowRun(workflowRunId) }.getOrNull()
        if (tasks.isNullOrEmpty()) return
        // 已完成任务贡献 100%，无论其 progress 字段值
        val avgProgress = tasks.sumOf { if (it.status == "completed") 100 else it.progress } / tasks.size
        runCatching { repo.updateProgressByWorkflowRunId(workflowRunId, avgProgress) }
        // 进度写失败不阻断主流程，静默忽略
    }
}

// =============================================================================
// WebenSourceDb —— weben_source 表的 JDBC 实现
// =============================================================================

class WebenSourceDb(private val db: Database) : WebenSourceRepo {

    suspend fun initialize() = withContext(Dispatchers.IO) {
        dbLogger.debug("WebenSourceDb.initialize: 初始化 weben_source 表及索引")
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
                // Schema migration: add workflow_run_id column if it doesn't exist yet
                @Suppress("SwallowedException")
                try {
                    stmt.execute("ALTER TABLE weben_source ADD COLUMN workflow_run_id TEXT")
                    dbLogger.debug("WebenSourceDb.initialize: workflow_run_id 列迁移成功")
                } catch (_: Exception) {
                    // 列已存在，无需处理
                    dbLogger.debug("WebenSourceDb.initialize: workflow_run_id 列已存在，跳过迁移")
                }
                // Schema migration: add progress column if it doesn't exist yet
                @Suppress("SwallowedException")
                try {
                    stmt.execute("ALTER TABLE weben_source ADD COLUMN progress INTEGER NOT NULL DEFAULT 0")
                    dbLogger.debug("WebenSourceDb.initialize: progress 列迁移成功")
                } catch (_: Exception) {
                    // 列已存在，无需处理
                    dbLogger.debug("WebenSourceDb.initialize: progress 列已存在，跳过迁移")
                }
            }
        }
        dbLogger.debug("WebenSourceDb.initialize: 初始化完成")
    }

    override suspend fun create(source: WebenSource) = withContext(Dispatchers.IO) {
        dbLogger.debug(
            "WebenSourceDb.create: INSERT id=${source.id}" +
            " sourceType=${source.sourceType} bvid=${source.bvid}" +
            " analysisStatus=${source.analysisStatus} materialId=${source.materialId}"
        )
        db.useConnection { conn ->
            conn.prepareStatement("""
                INSERT INTO weben_source
                    (id, material_id, url, title, source_type, bvid, duration_sec, quality_score, analysis_status, workflow_run_id, progress, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                ps.setStringOrNull(10, source.workflowRunId)
                ps.setInt(11, source.progress)
                ps.setLong(12, source.createdAt)
                val rows = ps.executeUpdate()
                dbLogger.debug("WebenSourceDb.create: 写入完成 id=${source.id} affectedRows=$rows")
            }
        }
        Unit
    }

    override suspend fun getById(id: String): WebenSource? = withContext(Dispatchers.IO) {
        dbLogger.debug("WebenSourceDb.getById: id=$id")
        val result = db.useConnection { conn ->
            conn.prepareStatement("SELECT * FROM weben_source WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toSource() else null }
            }
        }
        dbLogger.debug("WebenSourceDb.getById: id=$id found=${result != null}")
        result
    }

    override suspend fun listAll(materialId: String?): List<WebenSource> = withContext(Dispatchers.IO) {
        val sql = if (materialId != null)
            "SELECT * FROM weben_source WHERE material_id = ? ORDER BY created_at DESC"
        else
            "SELECT * FROM weben_source ORDER BY created_at DESC"
        db.useConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                if (materialId != null) ps.setString(1, materialId)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toSource()) } }
            }
        }
    }

    override suspend fun listPaged(materialId: String?, limit: Int, offset: Int): List<WebenSource> =
        withContext(Dispatchers.IO) {
            val sql = if (materialId != null)
                "SELECT * FROM weben_source WHERE material_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?"
            else
                "SELECT * FROM weben_source ORDER BY created_at DESC LIMIT ? OFFSET ?"
            db.useConnection { conn ->
                conn.prepareStatement(sql).use { ps ->
                    var idx = 1
                    if (materialId != null) ps.setString(idx++, materialId)
                    ps.setInt(idx++, limit); ps.setInt(idx, offset)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toSource()) } }
                }
            }
        }

    override suspend fun count(materialId: String?): Int = withContext(Dispatchers.IO) {
        val sql = if (materialId != null)
            "SELECT COUNT(*) FROM weben_source WHERE material_id = ?"
        else
            "SELECT COUNT(*) FROM weben_source"
        db.useConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                if (materialId != null) ps.setString(1, materialId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }
    }

    override suspend fun updateAnalysisStatus(id: String, status: String) = withContext(Dispatchers.IO) {
        dbLogger.debug("WebenSourceDb.updateAnalysisStatus: id=$id status=$status")
        db.useConnection { conn ->
            conn.prepareStatement("UPDATE weben_source SET analysis_status = ? WHERE id = ?").use { ps ->
                ps.setString(1, status)
                ps.setString(2, id)
                val rows = ps.executeUpdate()
                dbLogger.debug("WebenSourceDb.updateAnalysisStatus: id=$id status=$status affectedRows=$rows")
            }
        }
        Unit
    }

    override suspend fun updateProgress(id: String, progress: Int) = withContext(Dispatchers.IO) {
        dbLogger.debug("WebenSourceDb.updateProgress: id=$id progress=$progress")
        db.useConnection { conn ->
            conn.prepareStatement("UPDATE weben_source SET progress = ? WHERE id = ?").use { ps ->
                ps.setInt(1, progress)
                ps.setString(2, id)
                ps.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun updateProgressByWorkflowRunId(workflowRunId: String, progress: Int) = withContext(Dispatchers.IO) {
        dbLogger.debug("WebenSourceDb.updateProgressByWorkflowRunId: workflowRunId=$workflowRunId progress=$progress")
        db.useConnection { conn ->
            conn.prepareStatement("UPDATE weben_source SET progress = ? WHERE workflow_run_id = ?").use { ps ->
                ps.setInt(1, progress)
                ps.setString(2, workflowRunId)
                ps.executeUpdate()
            }
        }
        Unit
    }

    private fun ResultSet.toSource() = WebenSource(
        id             = getString("id"),
        materialId     = getString("material_id")?.takeIf { it.isNotBlank() },
        url            = getString("url"),
        title          = getString("title"),
        sourceType     = getString("source_type"),
        bvid           = getString("bvid"),
        durationSec    = getDouble("duration_sec").takeIf { !wasNull() },
        qualityScore   = getDouble("quality_score"),
        analysisStatus = getString("analysis_status"),
        workflowRunId  = getString("workflow_run_id"),
        progress       = getInt("progress"),
        createdAt      = getLong("created_at"),
    )
}

private fun java.sql.PreparedStatement.setStringOrNull(idx: Int, v: String?) {
    if (v != null) setString(idx, v) else setNull(idx, Types.VARCHAR)
}
