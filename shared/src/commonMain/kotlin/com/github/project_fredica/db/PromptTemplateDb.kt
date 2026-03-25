package com.github.project_fredica.db

// =============================================================================
// PromptTemplateDb —— prompt_template 表的 JDBC 实现
// =============================================================================
//
// 设计选择：
//   1. 与 TaskDb 保持一致，使用 db.useConnection { } + 原生 PreparedStatement，
//      而非 ktorm DSL，便于直接写 RETURNING、JSON 操作等 SQLite 专有语法。
//   2. source_type = "system" 的行由代码装载，不写入此表；
//      本 Db 只负责 source_type = "user" 的模板。
//   3. delete() 检查 source_type，禁止删除系统模板（双重防护，路由层也会拦截）。
// =============================================================================

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.warn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database

class PromptTemplateDb(private val db: Database) : PromptTemplateRepo {

    private val logger = createLogger()

    // ── 建表 ──────────────────────────────────────────────────────────────────

    /**
     * 创建 prompt_template 表（如不存在）。
     * 在 FredicaApi.jvm.kt 初始化时调用一次。
     */
    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS prompt_template (
                            id                    TEXT PRIMARY KEY,
                            name                  TEXT NOT NULL,
                            description           TEXT NOT NULL DEFAULT '',
                            category              TEXT NOT NULL DEFAULT '',
                            source_type           TEXT NOT NULL DEFAULT 'user',
                            script_language       TEXT NOT NULL DEFAULT 'javascript',
                            script_code           TEXT NOT NULL DEFAULT '',
                            schema_target         TEXT NOT NULL DEFAULT '',
                            based_on_template_id  TEXT,
                            created_at            INTEGER NOT NULL DEFAULT 0,
                            updated_at            INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent()
                    )
                    // 按 category + updated_at 查询是最常见的访问模式
                    stmt.execute(
                        "CREATE INDEX IF NOT EXISTS idx_prompt_template_category_updated " +
                            "ON prompt_template (category, updated_at DESC)"
                    )
                }
            }
            logger.debug("prompt_template 表初始化完成")
        }
    }

    // ── 查询 ──────────────────────────────────────────────────────────────────

    override suspend fun listUserTemplates(): List<PromptTemplate> = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                SELECT id, name, description, category, source_type, script_language,
                       script_code, schema_target, based_on_template_id, created_at, updated_at
                FROM prompt_template
                WHERE source_type = 'user'
                ORDER BY updated_at DESC
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(rs.toTemplate())
                    }
                }
            }
        }
    }

    override suspend fun listUserTemplatesByCategory(category: String): List<PromptTemplate> =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    """
                    SELECT id, name, description, category, source_type, script_language,
                           script_code, schema_target, based_on_template_id, created_at, updated_at
                    FROM prompt_template
                    WHERE source_type = 'user' AND category = ?
                    ORDER BY updated_at DESC
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, category)
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) add(rs.toTemplate())
                        }
                    }
                }
            }
        }

    override suspend fun getById(id: String): PromptTemplate? = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                SELECT id, name, description, category, source_type, script_language,
                       script_code, schema_target, based_on_template_id, created_at, updated_at
                FROM prompt_template
                WHERE id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.toTemplate() else null
                }
            }
        }
    }

    // ── 写入 ──────────────────────────────────────────────────────────────────

    override suspend fun save(template: PromptTemplate): PromptTemplate = withContext(Dispatchers.IO) {
        // 强制确保写入的是 user 模板，防止调用方误传 system
        val toSave = template.copy(sourceType = "user")
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO prompt_template
                    (id, name, description, category, source_type, script_language,
                     script_code, schema_target, based_on_template_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'user', ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, toSave.id)
                ps.setString(2, toSave.name)
                ps.setString(3, toSave.description)
                ps.setString(4, toSave.category)
                ps.setString(5, toSave.scriptLanguage)
                ps.setString(6, toSave.scriptCode)
                ps.setString(7, toSave.schemaTarget)
                ps.setString(8, toSave.basedOnTemplateId)
                ps.setLong(9, toSave.createdAt)
                ps.setLong(10, toSave.updatedAt)
                ps.executeUpdate()
            }
        }
        logger.debug("PromptTemplateDb.save: id=${toSave.id} name=${toSave.name}")
        toSave
    }

    // ── 删除 ──────────────────────────────────────────────────────────────────

    override suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            // 先查 source_type，系统模板不允许删除
            val sourceType = conn.prepareStatement(
                "SELECT source_type FROM prompt_template WHERE id = ?"
            ).use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getString("source_type") else null
                }
            }

            if (sourceType == null) {
                logger.debug("PromptTemplateDb.delete: id=$id 不存在，跳过")
                return@useConnection false
            }
            if (sourceType == "system") {
                // 正常的调用路径不会走到这里（路由层已拦截），记 warn 以便排查
                logger.warn(
                    "PromptTemplateDb.delete: 尝试删除系统模板 id=$id，已拒绝",
                    isHappensFrequently = false,
                    err = null
                )
                return@useConnection false
            }

            val affected = conn.prepareStatement(
                "DELETE FROM prompt_template WHERE id = ? AND source_type = 'user'"
            ).use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
            val deleted = affected > 0
            logger.debug("PromptTemplateDb.delete: id=$id deleted=$deleted")
            deleted
        }
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private fun java.sql.ResultSet.toTemplate() = PromptTemplate(
        id = getString("id"),
        name = getString("name"),
        description = getString("description") ?: "",
        category = getString("category") ?: "",
        sourceType = getString("source_type") ?: "user",
        scriptLanguage = getString("script_language") ?: "javascript",
        scriptCode = getString("script_code") ?: "",
        schemaTarget = getString("schema_target") ?: "",
        basedOnTemplateId = getString("based_on_template_id"),
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"),
    )
}
