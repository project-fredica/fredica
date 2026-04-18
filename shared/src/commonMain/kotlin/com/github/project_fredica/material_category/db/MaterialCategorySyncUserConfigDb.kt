package com.github.project_fredica.material_category.db

import com.github.project_fredica.material_category.model.MaterialCategorySyncUserConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database

class MaterialCategorySyncUserConfigDb(private val db: Database) : MaterialCategorySyncUserConfigRepo {

    override suspend fun getById(id: String): MaterialCategorySyncUserConfig? = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("SELECT * FROM material_category_sync_user_config WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rowToConfig(rs) else null
                }
            }
        }
    }

    override suspend fun findByPlatformInfoAndUser(
        platformInfoId: String,
        userId: String,
    ): MaterialCategorySyncUserConfig? = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                "SELECT * FROM material_category_sync_user_config WHERE platform_info_id = ? AND user_id = ?"
            ).use { ps ->
                ps.setString(1, platformInfoId)
                ps.setString(2, userId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rowToConfig(rs) else null
                }
            }
        }
    }

    override suspend fun listByPlatformInfo(platformInfoId: String): List<MaterialCategorySyncUserConfig> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<MaterialCategorySyncUserConfig>()
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM material_category_sync_user_config WHERE platform_info_id = ? ORDER BY created_at"
                ).use { ps ->
                    ps.setString(1, platformInfoId)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) result.add(rowToConfig(rs))
                    }
                }
            }
            result
        }

    override suspend fun listByUser(userId: String): List<MaterialCategorySyncUserConfig> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<MaterialCategorySyncUserConfig>()
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM material_category_sync_user_config WHERE user_id = ? ORDER BY created_at"
                ).use { ps ->
                    ps.setString(1, userId)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) result.add(rowToConfig(rs))
                    }
                }
            }
            result
        }

    override suspend fun subscriberCount(platformInfoId: String): Int = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM material_category_sync_user_config WHERE platform_info_id = ?"
            ).use { ps ->
                ps.setString(1, platformInfoId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    override suspend fun create(config: MaterialCategorySyncUserConfig): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO material_category_sync_user_config
                (id, platform_info_id, user_id, enabled, cron_expr, freshness_window_sec, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, config.id)
                ps.setString(2, config.platformInfoId)
                ps.setString(3, config.userId)
                ps.setInt(4, if (config.enabled) 1 else 0)
                ps.setString(5, config.cronExpr)
                ps.setInt(6, config.freshnessWindowSec)
                ps.setLong(7, config.createdAt)
                ps.setLong(8, config.updatedAt)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun deleteById(id: String): Boolean = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("DELETE FROM material_category_sync_user_config WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate() > 0
            }
        }
    }

    override suspend fun deleteByPlatformInfoId(platformInfoId: String): Int = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                "DELETE FROM material_category_sync_user_config WHERE platform_info_id = ?"
            ).use { ps ->
                ps.setString(1, platformInfoId)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun update(
        id: String,
        enabled: Boolean?,
        cronExpr: String?,
        freshnessWindowSec: Int?,
    ): Boolean = withContext(Dispatchers.IO) {
        val sets = mutableListOf<String>()
        val params = mutableListOf<Any>()
        if (enabled != null) { sets.add("enabled = ?"); params.add(if (enabled) 1 else 0) }
        if (cronExpr != null) { sets.add("cron_expr = ?"); params.add(cronExpr) }
        if (freshnessWindowSec != null) { sets.add("freshness_window_sec = ?"); params.add(freshnessWindowSec) }
        if (sets.isEmpty()) return@withContext false
        sets.add("updated_at = ?"); params.add(System.currentTimeMillis() / 1000L)
        params.add(id)
        val sql = "UPDATE material_category_sync_user_config SET ${sets.joinToString(", ")} WHERE id = ?"
        db.useConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                params.forEachIndexed { i, v ->
                    when (v) {
                        is String -> ps.setString(i + 1, v)
                        is Int -> ps.setInt(i + 1, v)
                        is Long -> ps.setLong(i + 1, v)
                        else -> ps.setString(i + 1, v.toString())
                    }
                }
                ps.executeUpdate() > 0
            }
        }
    }

    private fun rowToConfig(rs: java.sql.ResultSet): MaterialCategorySyncUserConfig = MaterialCategorySyncUserConfig(
        id = rs.getString("id"),
        platformInfoId = rs.getString("platform_info_id"),
        userId = rs.getString("user_id"),
        enabled = rs.getInt("enabled") == 1,
        cronExpr = rs.getString("cron_expr"),
        freshnessWindowSec = rs.getInt("freshness_window_sec"),
        createdAt = rs.getLong("created_at"),
        updatedAt = rs.getLong("updated_at"),
    )
}
