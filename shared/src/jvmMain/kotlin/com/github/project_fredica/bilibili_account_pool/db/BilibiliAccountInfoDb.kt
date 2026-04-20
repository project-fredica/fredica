package com.github.project_fredica.bilibili_account_pool.db

import com.github.project_fredica.bilibili_account_pool.model.BilibiliAccountInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database
import java.sql.ResultSet

class BilibiliAccountInfoDb(private val db: Database) : BilibiliAccountInfoRepo {

    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS bilibili_account_info (
                            account_id  TEXT PRIMARY KEY,
                            mid         TEXT NOT NULL DEFAULT '',
                            name        TEXT NOT NULL DEFAULT '',
                            face        TEXT NOT NULL DEFAULT '',
                            level       INTEGER NOT NULL DEFAULT 0,
                            sign        TEXT NOT NULL DEFAULT '',
                            coins       REAL NOT NULL DEFAULT 0,
                            fans        INTEGER NOT NULL DEFAULT 0,
                            following   INTEGER NOT NULL DEFAULT 0,
                            queried_at  INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent()
                    )
                }
            }
        }
    }

    override suspend fun upsert(info: BilibiliAccountInfo): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO bilibili_account_info
                    (account_id, mid, name, face, level, sign, coins, fans, following, queried_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(account_id) DO UPDATE SET
                    mid=excluded.mid,
                    name=excluded.name,
                    face=excluded.face,
                    level=excluded.level,
                    sign=excluded.sign,
                    coins=excluded.coins,
                    fans=excluded.fans,
                    following=excluded.following,
                    queried_at=excluded.queried_at
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, info.accountId)
                ps.setString(2, info.mid)
                ps.setString(3, info.name)
                ps.setString(4, info.face)
                ps.setInt(5, info.level)
                ps.setString(6, info.sign)
                ps.setDouble(7, info.coins)
                ps.setInt(8, info.fans)
                ps.setInt(9, info.following)
                ps.setLong(10, info.queriedAt)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun getByAccountId(accountId: String): BilibiliAccountInfo? =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM bilibili_account_info WHERE account_id=?"
                ).use { ps ->
                    ps.setString(1, accountId)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rowToInfo(rs) else null
                    }
                }
            }
        }

    override suspend fun getAll(): Map<String, BilibiliAccountInfo> = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                "SELECT * FROM bilibili_account_info"
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val map = mutableMapOf<String, BilibiliAccountInfo>()
                    while (rs.next()) {
                        val info = rowToInfo(rs)
                        map[info.accountId] = info
                    }
                    map
                }
            }
        }
    }

    override suspend fun deleteByAccountId(accountId: String): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                "DELETE FROM bilibili_account_info WHERE account_id=?"
            ).use { ps ->
                ps.setString(1, accountId)
                ps.executeUpdate()
            }
        }
    }

    private fun rowToInfo(rs: ResultSet) = BilibiliAccountInfo(
        accountId = rs.getString("account_id"),
        mid = rs.getString("mid"),
        name = rs.getString("name"),
        face = rs.getString("face"),
        level = rs.getInt("level"),
        sign = rs.getString("sign"),
        coins = rs.getDouble("coins"),
        fans = rs.getInt("fans"),
        following = rs.getInt("following"),
        queriedAt = rs.getLong("queried_at"),
    )
}
