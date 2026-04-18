package com.github.project_fredica.material_category.db

import com.github.project_fredica.material_category.model.MaterialCategoryAuditLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database

class MaterialCategoryAuditLogDb(private val db: Database) : MaterialCategoryAuditLogRepo {

    override suspend fun insert(log: MaterialCategoryAuditLog): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO material_category_audit_log
                (id, category_id, user_id, action, detail, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, log.id)
                ps.setString(2, log.categoryId)
                ps.setString(3, log.userId)
                ps.setString(4, log.action)
                ps.setString(5, log.detail)
                ps.setLong(6, log.createdAt)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun listByCategoryId(categoryId: String, limit: Int): List<MaterialCategoryAuditLog> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<MaterialCategoryAuditLog>()
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM material_category_audit_log WHERE category_id = ? ORDER BY created_at DESC LIMIT ?"
                ).use { ps ->
                    ps.setString(1, categoryId)
                    ps.setInt(2, limit)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) result.add(rowToLog(rs))
                    }
                }
            }
            result
        }

    override suspend fun listByUserId(userId: String, limit: Int): List<MaterialCategoryAuditLog> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<MaterialCategoryAuditLog>()
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM material_category_audit_log WHERE user_id = ? ORDER BY created_at DESC LIMIT ?"
                ).use { ps ->
                    ps.setString(1, userId)
                    ps.setInt(2, limit)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) result.add(rowToLog(rs))
                    }
                }
            }
            result
        }

    private fun rowToLog(rs: java.sql.ResultSet): MaterialCategoryAuditLog = MaterialCategoryAuditLog(
        id = rs.getString("id"),
        categoryId = rs.getString("category_id"),
        userId = rs.getString("user_id"),
        action = rs.getString("action"),
        detail = rs.getString("detail"),
        createdAt = rs.getLong("created_at"),
    )
}
