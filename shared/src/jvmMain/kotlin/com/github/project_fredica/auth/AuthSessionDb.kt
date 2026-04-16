package com.github.project_fredica.auth

// =============================================================================
// AuthSessionDb —— 登录 Session 持久化
// =============================================================================
//
// Session 生命周期：
//   创建：login() / initializeInstance() 成功后调用 createSession()
//   验证：每次请求 resolveIdentity() 查 token → 检查 expires_at / last_accessed_at
//   更新：每次请求成功后 fire-and-forget 更新 last_accessed_at（不阻塞响应）
//   删除：logout() 删单条；禁用用户时 deleteByUserId()；修改密码后 deleteByUserIdExcept()
//
// 过期策略（双重）：
//   - expires_at：绝对过期，7 天后强制失效，防止 token 永久有效
//   - last_accessed_at：活跃超时，24 小时无请求视为过期，减少僵尸 session
//   deleteExpired() 由定时任务调用，清理两种过期 session
//
// 并发 session 上限（MAX_SESSIONS_PER_USER = 5）：
//   超出时 FIFO 淘汰最旧的，防止同一用户无限创建 session 占用存储。
//   实现上先查再删，非原子操作，极端并发下可能短暂超出上限，可接受。
// =============================================================================

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

class AuthSessionDb(private val db: Database) : AuthSessionRepo {

    companion object {
        private const val SESSION_EXPIRY_SECONDS = 7L * 24 * 60 * 60  // 7 天
        private const val INACTIVITY_TIMEOUT_SECONDS = 24L * 60 * 60  // 24 小时无活动视为过期
        private const val MAX_SESSIONS_PER_USER = 5
    }

    // -- 建表 --

    override suspend fun initialize(): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS auth_session (
                        session_id       TEXT PRIMARY KEY,
                        token            TEXT NOT NULL UNIQUE,
                        user_id          TEXT NOT NULL REFERENCES user(id),
                        created_at       INTEGER NOT NULL,
                        expires_at       INTEGER NOT NULL,
                        last_accessed_at INTEGER NOT NULL,
                        user_agent       TEXT NOT NULL DEFAULT '',
                        ip_address       TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_auth_session_token ON auth_session(token)"
                )
            }
        }
    }

    // -- createSession --

    override suspend fun createSession(
        userId: String,
        userAgent: String,
        ipAddress: String,
    ): AuthSessionEntity = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString()
        val token = generateToken()
        val nowSec = System.currentTimeMillis() / 1000L
        val expiresAt = nowSec + SESSION_EXPIRY_SECONDS

        // FIFO 淘汰：超过 MAX_SESSIONS_PER_USER 时删最旧的
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                SELECT session_id FROM auth_session
                WHERE user_id = ?
                ORDER BY created_at ASC
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    val existingIds = mutableListOf<String>()
                    while (rs.next()) existingIds.add(rs.getString("session_id"))
                    // 如果已有 >= MAX_SESSIONS_PER_USER 个，删除最旧的直到剩 MAX-1 个
                    val toDelete = existingIds.size - (MAX_SESSIONS_PER_USER - 1)
                    if (toDelete > 0) {
                        conn.prepareStatement(
                            "DELETE FROM auth_session WHERE session_id = ?"
                        ).use { delPs ->
                            for (i in 0 until toDelete) {
                                delPs.setString(1, existingIds[i])
                                delPs.executeUpdate()
                            }
                        }
                    }
                }
            }

            // 插入新 session
            conn.prepareStatement(
                """
                INSERT INTO auth_session (session_id, token, user_id, created_at, expires_at, last_accessed_at, user_agent, ip_address)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, sessionId)
                ps.setString(2, token)
                ps.setString(3, userId)
                ps.setLong(4, nowSec)
                ps.setLong(5, expiresAt)
                ps.setLong(6, nowSec)
                ps.setString(7, userAgent)
                ps.setString(8, ipAddress)
                ps.executeUpdate()
            }
        }

        AuthSessionEntity(
            sessionId = sessionId,
            token = token,
            userId = userId,
            createdAt = nowSec,
            expiresAt = expiresAt,
            lastAccessedAt = nowSec,
            userAgent = userAgent,
            ipAddress = ipAddress,
        )
    }

    // -- findByToken --

    override suspend fun findByToken(token: String): AuthSessionEntity? = withContext(Dispatchers.IO) {
        var result: AuthSessionEntity? = null
        db.useConnection { conn ->
            conn.prepareStatement(
                "SELECT * FROM auth_session WHERE token = ?"
            ).use { ps ->
                ps.setString(1, token)
                ps.executeQuery().use { rs ->
                    if (rs.next()) result = rowToEntity(rs)
                }
            }
        }
        result
    }

    // -- findByUserId --

    override suspend fun findByUserId(userId: String): List<AuthSessionEntity> = withContext(Dispatchers.IO) {
        val result = mutableListOf<AuthSessionEntity>()
        db.useConnection { conn ->
            conn.prepareStatement(
                "SELECT * FROM auth_session WHERE user_id = ? ORDER BY created_at DESC"
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) result.add(rowToEntity(rs))
                }
            }
        }
        result
    }

    // -- updateLastAccessed --

    override suspend fun updateLastAccessed(sessionId: String): Unit = withContext(Dispatchers.IO) {
        val nowSec = System.currentTimeMillis() / 1000L
        db.useConnection { conn ->
            conn.prepareStatement(
                "UPDATE auth_session SET last_accessed_at = ? WHERE session_id = ?"
            ).use { ps ->
                ps.setLong(1, nowSec)
                ps.setString(2, sessionId)
                ps.executeUpdate()
            }
        }
    }

    // -- deleteBySessionId --

    override suspend fun deleteBySessionId(sessionId: String): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                "DELETE FROM auth_session WHERE session_id = ?"
            ).use { ps ->
                ps.setString(1, sessionId)
                ps.executeUpdate()
            }
        }
    }

    // -- deleteByUserId --

    override suspend fun deleteByUserId(userId: String): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                "DELETE FROM auth_session WHERE user_id = ?"
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeUpdate()
            }
        }
    }

    // -- deleteByUserIdExcept --

    override suspend fun deleteByUserIdExcept(
        userId: String,
        currentSessionId: String,
    ): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement(
                "DELETE FROM auth_session WHERE user_id = ? AND session_id != ?"
            ).use { ps ->
                ps.setString(1, userId)
                ps.setString(2, currentSessionId)
                ps.executeUpdate()
            }
        }
    }

    // -- deleteExpired --

    override suspend fun deleteExpired(): Unit = withContext(Dispatchers.IO) {
        val nowSec = System.currentTimeMillis() / 1000L
        val inactivityCutoff = nowSec - INACTIVITY_TIMEOUT_SECONDS
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                DELETE FROM auth_session
                WHERE expires_at < ? OR last_accessed_at < ?
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, nowSec)
                ps.setLong(2, inactivityCutoff)
                ps.executeUpdate()
            }
        }
    }

    // -- 内部工具 --

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun rowToEntity(rs: java.sql.ResultSet): AuthSessionEntity = AuthSessionEntity(
        sessionId = rs.getString("session_id"),
        token = rs.getString("token"),
        userId = rs.getString("user_id"),
        createdAt = rs.getLong("created_at"),
        expiresAt = rs.getLong("expires_at"),
        lastAccessedAt = rs.getLong("last_accessed_at"),
        userAgent = rs.getString("user_agent"),
        ipAddress = rs.getString("ip_address"),
    )
}
