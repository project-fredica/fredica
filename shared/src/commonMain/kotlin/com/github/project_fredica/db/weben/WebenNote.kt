package com.github.project_fredica.db.weben

// =============================================================================
// WebenNote —— 用户笔记
// =============================================================================
//
// 每条笔记归属于一个概念节点，当前为纯文本；Phase 2+ 可扩展为 Markdown。
// =============================================================================

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.ktorm.database.Database
import java.sql.ResultSet

@Serializable
data class WebenNote(
    /** UUID。 */
    val id: String,
    /** 所属概念（weben_concept.id）。 */
    @SerialName("concept_id") val conceptId: String,
    /** 笔记正文（当前为纯文本）。 */
    val content: String,
    /** 创建时间，Unix 秒。 */
    @SerialName("created_at") val createdAt: Long,
    /** 最后修改时间，Unix 秒。 */
    @SerialName("updated_at") val updatedAt: Long,
)

// =============================================================================
// WebenNoteRepo
// =============================================================================

interface WebenNoteRepo {
    suspend fun listByConcept(conceptId: String): List<WebenNote>
    /** 按 id 幂等写入（id 存在则更新 content + updated_at，否则插入）。 */
    suspend fun save(note: WebenNote)
    suspend fun deleteById(id: String)
}

// =============================================================================
// WebenNoteService
// =============================================================================

object WebenNoteService {
    private var _repo: WebenNoteRepo? = null
    val repo: WebenNoteRepo
        get() = _repo ?: error("WebenNoteService 未初始化，请先调用 initialize()")
    fun initialize(repo: WebenNoteRepo) { _repo = repo }
}

// =============================================================================
// WebenNoteDb —— weben_note 表的 JDBC 实现
// =============================================================================

class WebenNoteDb(private val db: Database) : WebenNoteRepo {

    suspend fun initialize() = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS weben_note (
                        id          TEXT    PRIMARY KEY,
                        concept_id  TEXT    NOT NULL,
                        content     TEXT    NOT NULL,
                        created_at  INTEGER NOT NULL,
                        updated_at  INTEGER NOT NULL
                    )
                """.trimIndent())
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_wn_concept ON weben_note(concept_id)")
            }
        }
    }

    override suspend fun listByConcept(conceptId: String): List<WebenNote> = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                "SELECT * FROM weben_note WHERE concept_id = ? ORDER BY updated_at DESC"
            ).use { ps ->
                ps.setString(1, conceptId)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toNote()) } }
            }
        }
    }

    override suspend fun save(note: WebenNote) = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("""
                INSERT INTO weben_note (id, concept_id, content, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET content = excluded.content, updated_at = excluded.updated_at
            """.trimIndent()).use { ps ->
                ps.setString(1, note.id)
                ps.setString(2, note.conceptId)
                ps.setString(3, note.content)
                ps.setLong(4, note.createdAt)
                ps.setLong(5, note.updatedAt)
                ps.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun deleteById(id: String) = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("DELETE FROM weben_note WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
        }
        Unit
    }

    private fun ResultSet.toNote() = WebenNote(
        id        = getString("id"),
        conceptId = getString("concept_id"),
        content   = getString("content"),
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"),
    )
}
