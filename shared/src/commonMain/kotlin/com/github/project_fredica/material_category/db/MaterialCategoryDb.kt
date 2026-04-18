package com.github.project_fredica.material_category.db

import com.github.project_fredica.material_category.model.MaterialCategory
import com.github.project_fredica.material_category.model.MaterialCategoryDefaults
import com.github.project_fredica.material_category.model.MaterialCategoryFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database
import java.util.UUID

class MaterialCategoryDb(private val db: Database) : MaterialCategoryRepo {

    suspend fun initialize() {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                withContext(Dispatchers.IO) {
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS material_category_simple (
                            id                  TEXT PRIMARY KEY,
                            owner_id            TEXT NOT NULL,
                            name                TEXT NOT NULL,
                            description         TEXT NOT NULL DEFAULT '',
                            allow_others_view   INTEGER NOT NULL DEFAULT 0,
                            allow_others_add    INTEGER NOT NULL DEFAULT 0,
                            allow_others_delete INTEGER NOT NULL DEFAULT 0,
                            created_at          INTEGER NOT NULL,
                            updated_at          INTEGER NOT NULL,
                            UNIQUE(owner_id, name)
                        )
                        """.trimIndent()
                    )
                    stmt.execute(
                        """
                        CREATE INDEX IF NOT EXISTS idx_category_simple_public
                        ON material_category_simple (allow_others_view) WHERE allow_others_view = 1
                        """.trimIndent()
                    )
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS material_category_rel (
                            material_id  TEXT NOT NULL,
                            category_id  TEXT NOT NULL,
                            added_by     TEXT NOT NULL DEFAULT 'user',
                            added_at     INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY (material_id, category_id)
                        )
                        """.trimIndent()
                    )
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS material_category_sync_platform_info (
                            id              TEXT PRIMARY KEY,
                            sync_type       TEXT NOT NULL,
                            platform_id     TEXT NOT NULL,
                            platform_config TEXT NOT NULL DEFAULT '{}',
                            display_name    TEXT NOT NULL DEFAULT '',
                            category_id     TEXT NOT NULL,
                            last_synced_at  INTEGER,
                            sync_cursor     TEXT NOT NULL DEFAULT '',
                            item_count      INTEGER NOT NULL DEFAULT 0,
                            sync_state      TEXT NOT NULL DEFAULT 'idle',
                            last_error      TEXT,
                            fail_count      INTEGER NOT NULL DEFAULT 0,
                            created_at      INTEGER NOT NULL,
                            updated_at      INTEGER NOT NULL,
                            UNIQUE(sync_type, platform_id)
                        )
                        """.trimIndent()
                    )
                    stmt.execute(
                        """
                        CREATE INDEX IF NOT EXISTS idx_sync_platform_info_state_time
                        ON material_category_sync_platform_info (sync_state, last_synced_at)
                        """.trimIndent()
                    )
                    stmt.execute(
                        """
                        CREATE INDEX IF NOT EXISTS idx_sync_platform_info_category
                        ON material_category_sync_platform_info (category_id)
                        """.trimIndent()
                    )
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS material_category_sync_user_config (
                            id                    TEXT PRIMARY KEY,
                            platform_info_id      TEXT NOT NULL,
                            user_id               TEXT NOT NULL,
                            enabled               INTEGER NOT NULL DEFAULT 1,
                            cron_expr             TEXT NOT NULL DEFAULT '0 */6 * * *',
                            freshness_window_sec  INTEGER NOT NULL DEFAULT 3600,
                            created_at            INTEGER NOT NULL,
                            updated_at            INTEGER NOT NULL,
                            UNIQUE(platform_info_id, user_id)
                        )
                        """.trimIndent()
                    )
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS material_category_sync_item (
                            id                TEXT PRIMARY KEY,
                            platform_info_id  TEXT NOT NULL,
                            material_id       TEXT NOT NULL,
                            platform_item_id  TEXT NOT NULL DEFAULT '',
                            synced_at         INTEGER NOT NULL,
                            UNIQUE(platform_info_id, material_id)
                        )
                        """.trimIndent()
                    )
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS material_category_audit_log (
                            id          TEXT PRIMARY KEY,
                            category_id TEXT NOT NULL,
                            user_id     TEXT NOT NULL DEFAULT '',
                            action      TEXT NOT NULL,
                            detail      TEXT NOT NULL DEFAULT '{}',
                            created_at  INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    stmt.execute(
                        """
                        CREATE INDEX IF NOT EXISTS idx_audit_log_category
                        ON material_category_audit_log (category_id, created_at)
                        """.trimIndent()
                    )
                }
            }
        }
    }

    override suspend fun listForUser(userId: String): List<MaterialCategory> = withContext(Dispatchers.IO) {
        val result = mutableListOf<MaterialCategory>()
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                SELECT c.id, c.owner_id, c.name, c.description,
                       c.allow_others_view, c.allow_others_add, c.allow_others_delete,
                       c.created_at, c.updated_at,
                       COUNT(mcr.material_id) AS material_count,
                       CASE WHEN c.owner_id = ? THEN 1 ELSE 0 END AS is_mine
                FROM material_category_simple c
                LEFT JOIN material_category_rel mcr ON c.id = mcr.category_id
                WHERE c.owner_id = ? OR c.allow_others_view = 1
                GROUP BY c.id
                ORDER BY is_mine DESC, c.name
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, userId)
                ps.setString(2, userId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        result.add(rowToCategory(rs))
                    }
                }
            }
        }
        result
    }

    override suspend fun listMine(userId: String): List<MaterialCategory> = withContext(Dispatchers.IO) {
        val result = mutableListOf<MaterialCategory>()
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                SELECT c.id, c.owner_id, c.name, c.description,
                       c.allow_others_view, c.allow_others_add, c.allow_others_delete,
                       c.created_at, c.updated_at,
                       COUNT(mcr.material_id) AS material_count,
                       1 AS is_mine
                FROM material_category_simple c
                LEFT JOIN material_category_rel mcr ON c.id = mcr.category_id
                WHERE c.owner_id = ?
                GROUP BY c.id
                ORDER BY c.name
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        result.add(rowToCategory(rs))
                    }
                }
            }
        }
        result
    }

    override suspend fun listFiltered(
        userId: String?,
        filter: MaterialCategoryFilter,
        search: String?,
        offset: Int,
        limit: Int,
    ): Pair<List<MaterialCategory>, Int> = withContext(Dispatchers.IO) {
        val clampedLimit = limit.coerceIn(1, 100)
        val clampedOffset = offset.coerceAtLeast(0)

        val whereClauses = mutableListOf<String>()
        val params = mutableListOf<Any>()

        if (userId == null) {
            whereClauses.add("c.allow_others_view = 1")
        } else {
            when (filter) {
                MaterialCategoryFilter.ALL -> {
                    whereClauses.add("(c.owner_id = ? OR c.allow_others_view = 1)")
                    params.add(userId)
                }
                MaterialCategoryFilter.MINE -> {
                    whereClauses.add("c.owner_id = ?")
                    whereClauses.add("c.id NOT IN (SELECT category_id FROM material_category_sync_platform_info)")
                    params.add(userId)
                }
                MaterialCategoryFilter.SYNC -> {
                    whereClauses.add("c.id IN (SELECT category_id FROM material_category_sync_platform_info)")
                }
                MaterialCategoryFilter.PUBLIC -> {
                    whereClauses.add("c.allow_others_view = 1")
                }
            }
        }

        if (!search.isNullOrBlank()) {
            whereClauses.add("c.name LIKE '%' || ? || '%'")
            params.add(search.trim())
        }

        val whereStr = if (whereClauses.isNotEmpty()) "WHERE ${whereClauses.joinToString(" AND ")}" else ""
        val isMineExpr = if (userId != null) "CASE WHEN c.owner_id = ? THEN 1 ELSE 0 END" else "0"

        val countSql = "SELECT COUNT(*) FROM material_category_simple c $whereStr"
        val dataSql = """
            SELECT c.id, c.owner_id, c.name, c.description,
                   c.allow_others_view, c.allow_others_add, c.allow_others_delete,
                   c.created_at, c.updated_at,
                   COUNT(mcr.material_id) AS material_count,
                   $isMineExpr AS is_mine
            FROM material_category_simple c
            LEFT JOIN material_category_rel mcr ON c.id = mcr.category_id
            $whereStr
            GROUP BY c.id
            ORDER BY is_mine DESC, c.created_at DESC
            LIMIT ? OFFSET ?
        """.trimIndent()

        db.useConnection { conn ->
            val total = conn.prepareStatement(countSql).use { ps ->
                var idx = 1
                for (p in params) {
                    when (p) {
                        is String -> ps.setString(idx++, p)
                        else -> ps.setString(idx++, p.toString())
                    }
                }
                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }

            val items = mutableListOf<MaterialCategory>()
            conn.prepareStatement(dataSql).use { ps ->
                var idx = 1
                if (userId != null) {
                    ps.setString(idx++, userId)
                }
                for (p in params) {
                    when (p) {
                        is String -> ps.setString(idx++, p)
                        else -> ps.setString(idx++, p.toString())
                    }
                }
                ps.setInt(idx++, clampedLimit)
                ps.setInt(idx, clampedOffset)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        items.add(rowToCategory(rs))
                    }
                }
            }

            Pair(items, total)
        }
    }

    override suspend fun getById(id: String): MaterialCategory? = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                SELECT c.id, c.owner_id, c.name, c.description,
                       c.allow_others_view, c.allow_others_add, c.allow_others_delete,
                       c.created_at, c.updated_at,
                       COUNT(mcr.material_id) AS material_count,
                       1 AS is_mine
                FROM material_category_simple c
                LEFT JOIN material_category_rel mcr ON c.id = mcr.category_id
                WHERE c.id = ?
                GROUP BY c.id
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rowToCategory(rs) else null
                }
            }
        }
    }

    override suspend fun create(ownerId: String, name: String, description: String): MaterialCategory {
        val nowSec = System.currentTimeMillis() / 1000L
        val newId = UUID.randomUUID().toString()
        return withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO material_category_simple
                    (id, owner_id, name, description, allow_others_view, allow_others_add, allow_others_delete, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 0, 0, 0, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, newId)
                    ps.setString(2, ownerId)
                    ps.setString(3, name.trim())
                    ps.setString(4, description)
                    ps.setLong(5, nowSec)
                    ps.setLong(6, nowSec)
                    ps.executeUpdate()
                }
            }
            MaterialCategory(
                id = newId,
                ownerId = ownerId,
                name = name.trim(),
                description = description,
                allowOthersView = false,
                allowOthersAdd = false,
                allowOthersDelete = false,
                materialCount = 0,
                isMine = true,
                createdAt = nowSec,
                updatedAt = nowSec,
            )
        }
    }

    override suspend fun insertOrIgnore(category: MaterialCategory): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                INSERT OR IGNORE INTO material_category_simple
                (id, owner_id, name, description, allow_others_view, allow_others_add, allow_others_delete, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, category.id)
                ps.setString(2, category.ownerId)
                ps.setString(3, category.name)
                ps.setString(4, category.description)
                ps.setInt(5, if (category.allowOthersView) 1 else 0)
                ps.setInt(6, if (category.allowOthersAdd) 1 else 0)
                ps.setInt(7, if (category.allowOthersDelete) 1 else 0)
                ps.setLong(8, category.createdAt)
                ps.setLong(9, category.updatedAt)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun update(
        categoryId: String,
        userId: String,
        name: String?,
        description: String?,
        allowOthersView: Boolean?,
        allowOthersAdd: Boolean?,
        allowOthersDelete: Boolean?,
    ): Boolean = withContext(Dispatchers.IO) {
        val sets = mutableListOf<String>()
        val params = mutableListOf<Any>()
        if (name != null) { sets.add("name = ?"); params.add(name) }
        if (description != null) { sets.add("description = ?"); params.add(description) }
        if (allowOthersView != null) {
            sets.add("allow_others_view = ?"); params.add(if (allowOthersView) 1 else 0)
            if (!allowOthersView) {
                sets.add("allow_others_add = 0")
                sets.add("allow_others_delete = 0")
            }
        }
        if (allowOthersAdd != null && allowOthersView != false) {
            sets.add("allow_others_add = ?"); params.add(if (allowOthersAdd) 1 else 0)
        }
        if (allowOthersDelete != null && allowOthersView != false) {
            sets.add("allow_others_delete = ?"); params.add(if (allowOthersDelete) 1 else 0)
        }
        if (sets.isEmpty()) return@withContext false
        sets.add("updated_at = ?"); params.add(System.currentTimeMillis() / 1000L)
        params.add(categoryId)
        params.add(userId)
        val sql = "UPDATE material_category_simple SET ${sets.joinToString(", ")} WHERE id = ? AND owner_id = ?"
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

    override suspend fun deleteById(categoryId: String, userId: String): Boolean = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("DELETE FROM material_category_rel WHERE category_id = ?").use { ps ->
                ps.setString(1, categoryId)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "DELETE FROM material_category_simple WHERE id = ? AND owner_id = ?"
            ).use { ps ->
                ps.setString(1, categoryId)
                ps.setString(2, userId)
                ps.executeUpdate() > 0
            }
        }
    }

    override suspend fun linkMaterials(
        materialIds: List<String>,
        categoryIds: List<String>,
        addedBy: String,
    ): Unit = withContext(Dispatchers.IO) {
        if (materialIds.isEmpty() || categoryIds.isEmpty()) return@withContext
        val nowSec = System.currentTimeMillis() / 1000L
        db.useConnection { conn ->
            conn.prepareStatement(
                "INSERT OR IGNORE INTO material_category_rel (material_id, category_id, added_by, added_at) VALUES (?, ?, ?, ?)"
            ).use { ps ->
                for (mid in materialIds) {
                    for (cid in categoryIds) {
                        ps.setString(1, mid)
                        ps.setString(2, cid)
                        ps.setString(3, addedBy)
                        ps.setLong(4, nowSec)
                        ps.executeUpdate()
                    }
                }
            }
        }
    }

    override suspend fun setMaterialCategories(
        materialId: String,
        categoryIds: List<String>,
        addedBy: String,
    ): Unit = withContext(Dispatchers.IO) {
        val nowSec = System.currentTimeMillis() / 1000L
        db.useConnection { conn ->
            conn.prepareStatement(
                "DELETE FROM material_category_rel WHERE material_id = ?"
            ).use { ps ->
                ps.setString(1, materialId)
                ps.executeUpdate()
            }
            if (categoryIds.isNotEmpty()) {
                conn.prepareStatement(
                    "INSERT OR IGNORE INTO material_category_rel (material_id, category_id, added_by, added_at) VALUES (?, ?, ?, ?)"
                ).use { ps ->
                    for (cid in categoryIds) {
                        ps.setString(1, materialId)
                        ps.setString(2, cid)
                        ps.setString(3, addedBy)
                        ps.setLong(4, nowSec)
                        ps.executeUpdate()
                    }
                }
            }
        }
    }

    override suspend fun deleteByMaterialIdExcluding(
        materialId: String,
        excludeCategoryIds: List<String>,
        onlyAddedBy: String?,
    ): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            val placeholders = excludeCategoryIds.joinToString(",") { "?" }
            val addedByClause = if (onlyAddedBy != null) " AND added_by = ?" else ""
            val sql = if (excludeCategoryIds.isNotEmpty()) {
                "DELETE FROM material_category_rel WHERE material_id = ? AND category_id NOT IN ($placeholders)$addedByClause"
            } else {
                "DELETE FROM material_category_rel WHERE material_id = ?$addedByClause"
            }
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setString(idx++, materialId)
                for (cid in excludeCategoryIds) {
                    ps.setString(idx++, cid)
                }
                if (onlyAddedBy != null) {
                    ps.setString(idx, onlyAddedBy)
                }
                ps.executeUpdate()
            }
        }
    }

    override suspend fun findOrphanMaterialIds(categoryId: String): List<String> = withContext(Dispatchers.IO) {
        val result = mutableListOf<String>()
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                SELECT r1.material_id
                FROM material_category_rel r1
                WHERE r1.category_id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM material_category_rel r2
                      WHERE r2.material_id = r1.material_id AND r2.category_id != ?
                  )
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, categoryId)
                ps.setString(2, categoryId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        result.add(rs.getString("material_id"))
                    }
                }
            }
        }
        result
    }

    override suspend fun ensureUncategorized(userId: String) {
        val id = MaterialCategoryDefaults.uncategorizedId(userId)
        val nowSec = System.currentTimeMillis() / 1000L
        insertOrIgnore(
            MaterialCategory(
                id = id,
                ownerId = userId,
                name = MaterialCategoryDefaults.UNCATEGORIZED_NAME,
                description = "",
                allowOthersView = false,
                allowOthersAdd = false,
                allowOthersDelete = false,
                createdAt = nowSec,
                updatedAt = nowSec,
            )
        )
    }

    private fun rowToCategory(rs: java.sql.ResultSet): MaterialCategory = MaterialCategory(
        id = rs.getString("id"),
        ownerId = rs.getString("owner_id"),
        name = rs.getString("name"),
        description = rs.getString("description"),
        allowOthersView = rs.getInt("allow_others_view") == 1,
        allowOthersAdd = rs.getInt("allow_others_add") == 1,
        allowOthersDelete = rs.getInt("allow_others_delete") == 1,
        materialCount = rs.getInt("material_count"),
        isMine = rs.getInt("is_mine") == 1,
        createdAt = rs.getLong("created_at"),
        updatedAt = rs.getLong("updated_at"),
    )
}
