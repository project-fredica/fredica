package com.github.project_fredica.material_category.db

import com.github.project_fredica.material_category.model.MaterialCategorySyncPlatformInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database

class MaterialCategorySyncPlatformInfoDb(private val db: Database) : MaterialCategorySyncPlatformInfoRepo {

    override suspend fun getById(id: String): MaterialCategorySyncPlatformInfo? = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("SELECT * FROM material_category_sync_platform_info WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rowToInfo(rs) else null
                }
            }
        }
    }

    override suspend fun findByPlatformKey(syncType: String, platformId: String): MaterialCategorySyncPlatformInfo? =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM material_category_sync_platform_info WHERE sync_type = ? AND platform_id = ?"
                ).use { ps ->
                    ps.setString(1, syncType)
                    ps.setString(2, platformId)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rowToInfo(rs) else null
                    }
                }
            }
        }

    override suspend fun findByCategoryId(categoryId: String): MaterialCategorySyncPlatformInfo? =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM material_category_sync_platform_info WHERE category_id = ?"
                ).use { ps ->
                    ps.setString(1, categoryId)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rowToInfo(rs) else null
                    }
                }
            }
        }

    override suspend fun create(info: MaterialCategorySyncPlatformInfo): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO material_category_sync_platform_info
                (id, sync_type, platform_id, platform_config, display_name, category_id,
                 last_synced_at, sync_cursor, item_count, sync_state, last_error, fail_count,
                 created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, info.id)
                ps.setString(2, info.syncType)
                ps.setString(3, info.platformId)
                ps.setString(4, info.platformConfig)
                ps.setString(5, info.displayName)
                ps.setString(6, info.categoryId)
                if (info.lastSyncedAt != null) ps.setLong(7, info.lastSyncedAt) else ps.setNull(7, java.sql.Types.INTEGER)
                ps.setString(8, info.syncCursor)
                ps.setInt(9, info.itemCount)
                ps.setString(10, info.syncState)
                if (info.lastError != null) ps.setString(11, info.lastError) else ps.setNull(11, java.sql.Types.VARCHAR)
                ps.setInt(12, info.failCount)
                ps.setLong(13, info.createdAt)
                ps.setLong(14, info.updatedAt)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun deleteById(id: String): Boolean = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("DELETE FROM material_category_sync_item WHERE platform_info_id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM material_category_sync_user_config WHERE platform_info_id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM material_category_sync_platform_info WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate() > 0
            }
        }
    }

    override suspend fun updateAfterSyncSuccess(
        id: String,
        syncCursor: String,
        lastSyncedAt: Long,
        itemCount: Int,
    ): Unit = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000L
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                UPDATE material_category_sync_platform_info
                SET sync_cursor = ?, last_synced_at = ?, item_count = ?,
                    sync_state = 'idle', fail_count = 0, last_error = NULL, updated_at = ?
                WHERE id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, syncCursor)
                ps.setLong(2, lastSyncedAt)
                ps.setInt(3, itemCount)
                ps.setLong(4, now)
                ps.setString(5, id)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun updateAfterSyncFailure(id: String, error: String): Unit = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000L
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                UPDATE material_category_sync_platform_info
                SET sync_state = 'failed', fail_count = fail_count + 1, last_error = ?, updated_at = ?
                WHERE id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, error)
                ps.setLong(2, now)
                ps.setString(3, id)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun setSyncState(id: String, state: String): Unit = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000L
        db.useConnection { conn ->
            conn.prepareStatement(
                "UPDATE material_category_sync_platform_info SET sync_state = ?, updated_at = ? WHERE id = ?"
            ).use { ps ->
                ps.setString(1, state)
                ps.setLong(2, now)
                ps.setString(3, id)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun updateDisplayName(id: String, displayName: String): Unit = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000L
        db.useConnection { conn ->
            conn.prepareStatement(
                "UPDATE material_category_sync_platform_info SET display_name = ?, updated_at = ? WHERE id = ?"
            ).use { ps ->
                ps.setString(1, displayName)
                ps.setLong(2, now)
                ps.setString(3, id)
                ps.executeUpdate()
            }
        }
    }

    private fun rowToInfo(rs: java.sql.ResultSet): MaterialCategorySyncPlatformInfo {
        val lastSynced = rs.getLong("last_synced_at")
        val lastSyncedNull = rs.wasNull()
        val lastError = rs.getString("last_error")
        return MaterialCategorySyncPlatformInfo(
            id = rs.getString("id"),
            syncType = rs.getString("sync_type"),
            platformId = rs.getString("platform_id"),
            platformConfig = rs.getString("platform_config"),
            displayName = rs.getString("display_name"),
            categoryId = rs.getString("category_id"),
            lastSyncedAt = if (lastSyncedNull) null else lastSynced,
            syncCursor = rs.getString("sync_cursor"),
            itemCount = rs.getInt("item_count"),
            syncState = rs.getString("sync_state"),
            lastError = lastError,
            failCount = rs.getInt("fail_count"),
            createdAt = rs.getLong("created_at"),
            updatedAt = rs.getLong("updated_at"),
        )
    }
}
