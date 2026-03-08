package com.github.project_fredica.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database

class BilibiliSubtitleMetaCacheDb(private val db: Database) : BilibiliSubtitleMetaCacheRepo {

    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS bilibili_subtitle_meta_cache (
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
                        CREATE INDEX IF NOT EXISTS idx_bsmc_bvid_page
                        ON bilibili_subtitle_meta_cache(bvid, page_index, queried_at DESC)
                        """.trimIndent()
                    )
                }
            }
        }
    }

    override suspend fun insert(entry: BilibiliSubtitleMetaCache): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO bilibili_subtitle_meta_cache (bvid, page_index, queried_at, raw_result, is_success) VALUES (?, ?, ?, ?, ?)"
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

    override suspend fun queryBest(bvid: String, pageIndex: Int): BilibiliSubtitleMetaCache? =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM bilibili_subtitle_meta_cache WHERE bvid=? AND page_index=? AND is_success=1 ORDER BY queried_at DESC LIMIT 1"
                ).use { ps ->
                    ps.setString(1, bvid); ps.setInt(2, pageIndex)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) BilibiliSubtitleMetaCache(
                            id = rs.getLong("id"),
                            bvid = rs.getString("bvid"),
                            pageIndex = rs.getInt("page_index"),
                            queriedAt = rs.getLong("queried_at"),
                            rawResult = rs.getString("raw_result"),
                            isSuccess = rs.getInt("is_success") != 0,
                        ) else null
                    }
                }
            }
        }
}

class BilibiliSubtitleBodyCacheDb(private val db: Database) : BilibiliSubtitleBodyCacheRepo {

    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS bilibili_subtitle_body_cache (
                            id          INTEGER PRIMARY KEY AUTOINCREMENT,
                            url_key     TEXT NOT NULL,
                            queried_at  INTEGER NOT NULL,
                            raw_result  TEXT NOT NULL,
                            is_success  INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent()
                    )
                    stmt.execute(
                        """
                        CREATE INDEX IF NOT EXISTS idx_bsbc_url_key
                        ON bilibili_subtitle_body_cache(url_key, queried_at DESC)
                        """.trimIndent()
                    )
                }
            }
        }
    }

    override suspend fun insert(entry: BilibiliSubtitleBodyCache): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO bilibili_subtitle_body_cache (url_key, queried_at, raw_result, is_success) VALUES (?, ?, ?, ?)"
            ).use { ps ->
                ps.setString(1, entry.urlKey)
                ps.setLong(2, entry.queriedAt)
                ps.setString(3, entry.rawResult)
                ps.setInt(4, if (entry.isSuccess) 1 else 0)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun queryBest(urlKey: String): BilibiliSubtitleBodyCache? =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM bilibili_subtitle_body_cache WHERE url_key=? AND is_success=1 ORDER BY queried_at DESC LIMIT 1"
                ).use { ps ->
                    ps.setString(1, urlKey)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) BilibiliSubtitleBodyCache(
                            id = rs.getLong("id"),
                            urlKey = rs.getString("url_key"),
                            queriedAt = rs.getLong("queried_at"),
                            rawResult = rs.getString("raw_result"),
                            isSuccess = rs.getInt("is_success") != 0,
                        ) else null
                    }
                }
            }
        }
}
