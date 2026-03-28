package com.github.project_fredica.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database

class LlmResponseCacheDb(private val db: Database) : LlmResponseCacheRepo {

    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS llm_response_cache (
                            id              INTEGER PRIMARY KEY AUTOINCREMENT,
                            key_hash        TEXT NOT NULL UNIQUE,
                            cache_key       TEXT NOT NULL,
                            model_name      TEXT NOT NULL,
                            base_url        TEXT NOT NULL,
                            messages_json   TEXT NOT NULL,
                            response_text   TEXT NOT NULL,
                            is_valid        INTEGER NOT NULL DEFAULT 1,
                            created_at      INTEGER NOT NULL,
                            last_hit_at     INTEGER NOT NULL,
                            hit_count       INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent()
                    )
                    stmt.execute(
                        """
                        CREATE INDEX IF NOT EXISTS idx_lrc_hash ON llm_response_cache(key_hash)
                        """.trimIndent()
                    )
                }
            }
        }
    }

    override suspend fun findByHash(keyHash: String): LlmResponseCache? =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM llm_response_cache WHERE key_hash=? AND is_valid=1"
                ).use { ps ->
                    ps.setString(1, keyHash)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rowToCache(rs) else null
                    }
                }
            }
        }

    override suspend fun upsert(entry: LlmResponseCache): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO llm_response_cache
                    (key_hash, cache_key, model_name, base_url, messages_json, response_text, is_valid, created_at, last_hit_at, hit_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(key_hash) DO UPDATE SET
                    cache_key=excluded.cache_key,
                    model_name=excluded.model_name,
                    base_url=excluded.base_url,
                    messages_json=excluded.messages_json,
                    response_text=excluded.response_text,
                    is_valid=excluded.is_valid,
                    last_hit_at=excluded.last_hit_at,
                    hit_count=excluded.hit_count
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, entry.keyHash)
                ps.setString(2, entry.cacheKey)
                ps.setString(3, entry.modelName)
                ps.setString(4, entry.baseUrl)
                ps.setString(5, entry.messagesJson)
                ps.setString(6, entry.responseText)
                ps.setInt(7, if (entry.isValid) 1 else 0)
                ps.setLong(8, entry.createdAt)
                ps.setLong(9, entry.lastHitAt)
                ps.setInt(10, entry.hitCount)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun updateHit(keyHash: String, hitAt: Long): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                "UPDATE llm_response_cache SET last_hit_at=?, hit_count=hit_count+1 WHERE key_hash=?"
            ).use { ps ->
                ps.setLong(1, hitAt)
                ps.setString(2, keyHash)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun invalidate(keyHash: String): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                "UPDATE llm_response_cache SET is_valid=0 WHERE key_hash=?"
            ).use { ps ->
                ps.setString(1, keyHash)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun invalidateByModel(modelName: String, baseUrl: String): Unit =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "UPDATE llm_response_cache SET is_valid=0 WHERE model_name=? AND base_url=?"
                ).use { ps ->
                    ps.setString(1, modelName)
                    ps.setString(2, baseUrl)
                    ps.executeUpdate()
                }
            }
        }

    private fun rowToCache(rs: java.sql.ResultSet) = LlmResponseCache(
        id = rs.getLong("id"),
        keyHash = rs.getString("key_hash"),
        cacheKey = rs.getString("cache_key"),
        modelName = rs.getString("model_name"),
        baseUrl = rs.getString("base_url"),
        messagesJson = rs.getString("messages_json"),
        responseText = rs.getString("response_text"),
        isValid = rs.getInt("is_valid") != 0,
        createdAt = rs.getLong("created_at"),
        lastHitAt = rs.getLong("last_hit_at"),
        hitCount = rs.getInt("hit_count"),
    )
}
