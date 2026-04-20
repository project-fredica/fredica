package com.github.project_fredica.bilibili_account_pool.db

import com.github.project_fredica.bilibili_account_pool.model.BilibiliAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database
import java.sql.ResultSet

class BilibiliAccountPoolDb(private val db: Database) : BilibiliAccountPoolRepo {

    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS bilibili_account_pool (
                            id                  TEXT PRIMARY KEY,
                            label               TEXT NOT NULL DEFAULT '',
                            is_anonymous        INTEGER NOT NULL DEFAULT 0,
                            is_default          INTEGER NOT NULL DEFAULT 0,
                            sessdata            TEXT NOT NULL DEFAULT '',
                            bili_jct            TEXT NOT NULL DEFAULT '',
                            buvid3              TEXT NOT NULL DEFAULT '',
                            buvid4              TEXT NOT NULL DEFAULT '',
                            dedeuserid          TEXT NOT NULL DEFAULT '',
                            ac_time_value       TEXT NOT NULL DEFAULT '',
                            proxy               TEXT NOT NULL DEFAULT '',
                            impersonate         TEXT NOT NULL DEFAULT 'chrome',
                            rate_limit_sec      REAL NOT NULL DEFAULT 1.0,
                            sort_order          INTEGER NOT NULL DEFAULT 0,
                            last_ip             TEXT NOT NULL DEFAULT '',
                            last_ip_checked_at  INTEGER NOT NULL DEFAULT 0,
                            created_at          INTEGER NOT NULL,
                            updated_at          INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    @Suppress("SwallowedException")
                    try {
                        stmt.execute("ALTER TABLE bilibili_account_pool ADD COLUMN impersonate TEXT NOT NULL DEFAULT 'chrome'")
                    } catch (_: Exception) { }
                }
            }
        }
    }

    override suspend fun getAll(): List<BilibiliAccount> = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                "SELECT * FROM bilibili_account_pool ORDER BY sort_order ASC, created_at ASC"
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val list = mutableListOf<BilibiliAccount>()
                    while (rs.next()) list.add(rowToAccount(rs))
                    list
                }
            }
        }
    }

    override suspend fun getById(id: String): BilibiliAccount? = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                "SELECT * FROM bilibili_account_pool WHERE id=?"
            ).use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rowToAccount(rs) else null
                }
            }
        }
    }

    override suspend fun getDefault(): BilibiliAccount? = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                "SELECT * FROM bilibili_account_pool WHERE is_default=1 LIMIT 1"
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    if (rs.next()) rowToAccount(rs) else null
                }
            }
        }
    }

    override suspend fun upsertAll(accounts: List<BilibiliAccount>): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO bilibili_account_pool
                    (id, label, is_anonymous, is_default, sessdata, bili_jct, buvid3, buvid4,
                     dedeuserid, ac_time_value, proxy, impersonate, rate_limit_sec, sort_order,
                     last_ip, last_ip_checked_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    label=excluded.label,
                    is_anonymous=excluded.is_anonymous,
                    is_default=excluded.is_default,
                    sessdata=excluded.sessdata,
                    bili_jct=excluded.bili_jct,
                    buvid3=excluded.buvid3,
                    buvid4=excluded.buvid4,
                    dedeuserid=excluded.dedeuserid,
                    ac_time_value=excluded.ac_time_value,
                    proxy=excluded.proxy,
                    impersonate=excluded.impersonate,
                    rate_limit_sec=excluded.rate_limit_sec,
                    sort_order=excluded.sort_order,
                    updated_at=excluded.updated_at
                """.trimIndent()
            ).use { ps ->
                for (a in accounts) {
                    ps.setString(1, a.id)
                    ps.setString(2, a.label)
                    ps.setInt(3, if (a.isAnonymous) 1 else 0)
                    ps.setInt(4, if (a.isDefault) 1 else 0)
                    ps.setString(5, a.bilibiliSessdata)
                    ps.setString(6, a.bilibiliBiliJct)
                    ps.setString(7, a.bilibiliBuvid3)
                    ps.setString(8, a.bilibiliBuvid4)
                    ps.setString(9, a.bilibiliDedeuserid)
                    ps.setString(10, a.bilibiliAcTimeValue)
                    ps.setString(11, a.bilibiliProxy)
                    ps.setString(12, a.bilibiliImpersonate)
                    ps.setDouble(13, a.rateLimitSec)
                    ps.setInt(14, a.sortOrder)
                    ps.setString(15, a.lastIp)
                    ps.setLong(16, a.lastIpCheckedAt)
                    ps.setLong(17, a.createdAt)
                    ps.setLong(18, a.updatedAt)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    override suspend fun deleteById(id: String): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                "DELETE FROM bilibili_account_pool WHERE id=?"
            ).use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun deleteAll(): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM bilibili_account_pool")
            }
        }
    }

    override suspend fun updateIpCheckResult(id: String, ip: String, checkedAt: Long): Unit =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "UPDATE bilibili_account_pool SET last_ip=?, last_ip_checked_at=?, updated_at=? WHERE id=?"
                ).use { ps ->
                    ps.setString(1, ip)
                    ps.setLong(2, checkedAt)
                    ps.setLong(3, checkedAt)
                    ps.setString(4, id)
                    ps.executeUpdate()
                }
            }
        }

    private fun rowToAccount(rs: ResultSet) = BilibiliAccount(
        id = rs.getString("id"),
        label = rs.getString("label"),
        isAnonymous = rs.getInt("is_anonymous") != 0,
        isDefault = rs.getInt("is_default") != 0,
        bilibiliSessdata = rs.getString("sessdata"),
        bilibiliBiliJct = rs.getString("bili_jct"),
        bilibiliBuvid3 = rs.getString("buvid3"),
        bilibiliBuvid4 = rs.getString("buvid4"),
        bilibiliDedeuserid = rs.getString("dedeuserid"),
        bilibiliAcTimeValue = rs.getString("ac_time_value"),
        bilibiliProxy = rs.getString("proxy"),
        bilibiliImpersonate = rs.getString("impersonate"),
        rateLimitSec = rs.getDouble("rate_limit_sec"),
        sortOrder = rs.getInt("sort_order"),
        lastIp = rs.getString("last_ip"),
        lastIpCheckedAt = rs.getLong("last_ip_checked_at"),
        createdAt = rs.getLong("created_at"),
        updatedAt = rs.getLong("updated_at"),
    )
}
