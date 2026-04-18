package com.github.project_fredica.material_category.db

import com.github.project_fredica.material_category.model.MaterialCategorySyncItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database

class MaterialCategorySyncItemDb(private val db: Database) : MaterialCategorySyncItemRepo {

    override suspend fun upsert(item: MaterialCategorySyncItem): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO material_category_sync_item
                (id, platform_info_id, material_id, platform_item_id, synced_at)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, item.id)
                ps.setString(2, item.platformInfoId)
                ps.setString(3, item.materialId)
                ps.setString(4, item.platformItemId)
                ps.setLong(5, item.syncedAt)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun upsertBatch(items: List<MaterialCategorySyncItem>): Unit = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO material_category_sync_item
                (id, platform_info_id, material_id, platform_item_id, synced_at)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                for (item in items) {
                    ps.setString(1, item.id)
                    ps.setString(2, item.platformInfoId)
                    ps.setString(3, item.materialId)
                    ps.setString(4, item.platformItemId)
                    ps.setLong(5, item.syncedAt)
                    ps.executeUpdate()
                }
            }
        }
    }

    override suspend fun listByPlatformInfo(platformInfoId: String): List<MaterialCategorySyncItem> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<MaterialCategorySyncItem>()
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM material_category_sync_item WHERE platform_info_id = ? ORDER BY synced_at DESC"
                ).use { ps ->
                    ps.setString(1, platformInfoId)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) result.add(rowToItem(rs))
                    }
                }
            }
            result
        }

    override suspend fun countByPlatformInfo(platformInfoId: String): Int = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM material_category_sync_item WHERE platform_info_id = ?"
            ).use { ps ->
                ps.setString(1, platformInfoId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    override suspend fun deleteByPlatformInfoId(platformInfoId: String): Int = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                "DELETE FROM material_category_sync_item WHERE platform_info_id = ?"
            ).use { ps ->
                ps.setString(1, platformInfoId)
                ps.executeUpdate()
            }
        }
    }

    private fun rowToItem(rs: java.sql.ResultSet): MaterialCategorySyncItem = MaterialCategorySyncItem(
        id = rs.getString("id"),
        platformInfoId = rs.getString("platform_info_id"),
        materialId = rs.getString("material_id"),
        platformItemId = rs.getString("platform_item_id"),
        syncedAt = rs.getLong("synced_at"),
    )
}
