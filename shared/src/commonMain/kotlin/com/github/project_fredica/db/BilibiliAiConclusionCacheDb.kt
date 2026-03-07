package com.github.project_fredica.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database
import kotlin.math.max

class BilibiliAiConclusionCacheDb(private val db: Database) : BilibiliAiConclusionCacheRepo {

    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS bilibili_ai_conclusion_cache (
                            id          INTEGER PRIMARY KEY AUTOINCREMENT,
                            bvid        TEXT NOT NULL,
                            page_index  INTEGER NOT NULL DEFAULT 0,
                            queried_at  INTEGER NOT NULL,
                            raw_result  TEXT NOT NULL,
                            is_success  INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent()
                    )
                    stmt.execute(
                        """
                        CREATE INDEX IF NOT EXISTS idx_bac_bvid_page
                        ON bilibili_ai_conclusion_cache(bvid, page_index, queried_at DESC)
                        """.trimIndent()
                    )
                }
            }
        }
    }

    override suspend fun insert(entry: BilibiliAiConclusionCache): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO bilibili_ai_conclusion_cache (bvid, page_index, queried_at, raw_result, is_success)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, entry.bvid)
                ps.setInt(2, entry.pageIndex)
                ps.setLong(3, entry.queriedAt)
                ps.setString(4, entry.rawResult)
                ps.setInt(5, if (entry.isSuccess) 1 else 0)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun queryBest(
        bvid: String,
        pageIndex: Int,
        expireTime: Long
    ): BilibiliAiConclusionCache? =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    """
                    SELECT * FROM bilibili_ai_conclusion_cache
                    WHERE bvid=? AND page_index=? AND is_success=1
                    ORDER BY queried_at DESC LIMIT 1
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, bvid); ps.setInt(2, pageIndex)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) return@withContext rowToCache(rs)
                    }
                }
                val expireAt = System.currentTimeMillis() / 1000L - max(0L, expireTime)
                conn.prepareStatement(
                    """
                    SELECT * FROM bilibili_ai_conclusion_cache
                    WHERE bvid=? AND page_index=? AND is_success=0 AND queried_at >= ?
                      AND raw_result NOT LIKE '%账号未登录%'
                    ORDER BY queried_at DESC LIMIT 1
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, bvid); ps.setInt(2, pageIndex); ps.setLong(3, expireAt)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rowToCache(rs) else null
                    }
                }
            }
        }

    private fun rowToCache(rs: java.sql.ResultSet) = BilibiliAiConclusionCache(
        id = rs.getLong("id"),
        bvid = rs.getString("bvid"),
        pageIndex = rs.getInt("page_index"),
        queriedAt = rs.getLong("queried_at"),
        rawResult = rs.getString("raw_result"),
        isSuccess = rs.getInt("is_success") != 0,
    )
}
