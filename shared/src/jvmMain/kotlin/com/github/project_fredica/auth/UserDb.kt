package com.github.project_fredica.auth

// =============================================================================
// UserDb —— 用户表持久化
// =============================================================================
//
// 表约束说明（SQLite CHECK）：
//   - username：2-32 字符，首字母必须是字母，后续可含字母/数字/下划线/连字符
//     原因：防止纯数字用户名与 ID 混淆，保证 URL 安全
//   - display_name：1-64 字符（trim 后），允许中文等 Unicode
//   - role：只允许 'root' / 'tenant'，GUEST 不存储在 user 表（无账号）
//   - status：只允许 'active' / 'disabled'
//
// findByUsername 使用 COLLATE NOCASE：
//   登录时大小写不敏感（alice = Alice），但存储保留原始大小写（显示用）。
//
// updatePasswordAndPrivateKey：
//   修改密码时必须同步重加密私钥（私钥由密码派生的 IMK 加密），
//   两者必须原子更新，否则私钥将无法用新密码解密。
// =============================================================================

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database
import java.time.Instant
import java.util.UUID

class UserDb(private val db: Database) : UserRepo {

    // -- 建表 --

    override suspend fun initialize(): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS user (
                        id              TEXT PRIMARY KEY,
                        username        TEXT NOT NULL UNIQUE,
                        display_name    TEXT NOT NULL,
                        password_hash   TEXT NOT NULL,
                        role            TEXT NOT NULL DEFAULT 'tenant',
                        permissions     TEXT NOT NULL DEFAULT '',
                        public_key_pem  TEXT NOT NULL DEFAULT '',
                        private_key_pem TEXT NOT NULL DEFAULT '',
                        status          TEXT NOT NULL DEFAULT 'active',
                        created_at      TEXT NOT NULL,
                        updated_at      TEXT NOT NULL,
                        last_login_at   TEXT,

                        CHECK (length(username) >= 2 AND length(username) <= 32),
                        CHECK (username GLOB '[a-zA-Z][a-zA-Z0-9_-]*'),
                        CHECK (length(trim(display_name)) >= 1 AND length(display_name) <= 64),
                        CHECK (role IN ('root', 'tenant')),
                        CHECK (status IN ('active', 'disabled'))
                    )
                    """.trimIndent()
                )
            }
        }
    }

    // -- createUser --

    override suspend fun createUser(
        username: String,
        displayName: String,
        passwordHash: String,
        role: String,
        publicKeyPem: String,
        encryptedPrivateKeyPem: String,
    ): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO user (id, username, display_name, password_hash, role,
                    public_key_pem, private_key_pem, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'active', ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, username)
                ps.setString(3, displayName)
                ps.setString(4, passwordHash)
                ps.setString(5, role)
                ps.setString(6, publicKeyPem)
                ps.setString(7, encryptedPrivateKeyPem)
                ps.setString(8, now)
                ps.setString(9, now)
                ps.executeUpdate()
            }
        }
        id
    }

    // -- findByUsername（大小写不敏感） --

    override suspend fun findByUsername(username: String): UserEntity? = withContext(Dispatchers.IO) {
        var result: UserEntity? = null
        db.useConnection { conn ->
            conn.prepareStatement(
                "SELECT * FROM user WHERE username = ? COLLATE NOCASE"
            ).use { ps ->
                ps.setString(1, username)
                ps.executeQuery().use { rs ->
                    if (rs.next()) result = rowToEntity(rs)
                }
            }
        }
        result
    }

    // -- findById --

    override suspend fun findById(userId: String): UserEntity? = withContext(Dispatchers.IO) {
        var result: UserEntity? = null
        db.useConnection { conn ->
            conn.prepareStatement("SELECT * FROM user WHERE id = ?").use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) result = rowToEntity(rs)
                }
            }
        }
        result
    }

    // -- listAll --

    override suspend fun listAll(): List<UserEntity> = withContext(Dispatchers.IO) {
        val result = mutableListOf<UserEntity>()
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT * FROM user ORDER BY created_at ASC").use { rs ->
                    while (rs.next()) result.add(rowToEntity(rs))
                }
            }
        }
        result
    }

    // -- updateStatus --

    override suspend fun updateStatus(userId: String, status: String): Unit = withContext(Dispatchers.IO) {
        val now = Instant.now().toString()
        db.useConnection { conn ->
            conn.prepareStatement(
                "UPDATE user SET status = ?, updated_at = ? WHERE id = ?"
            ).use { ps ->
                ps.setString(1, status)
                ps.setString(2, now)
                ps.setString(3, userId)
                ps.executeUpdate()
            }
        }
    }

    // -- updatePassword --

    override suspend fun updatePassword(userId: String, newPasswordHash: String): Unit = withContext(Dispatchers.IO) {
        val now = Instant.now().toString()
        db.useConnection { conn ->
            conn.prepareStatement(
                "UPDATE user SET password_hash = ?, updated_at = ? WHERE id = ?"
            ).use { ps ->
                ps.setString(1, newPasswordHash)
                ps.setString(2, now)
                ps.setString(3, userId)
                ps.executeUpdate()
            }
        }
    }

    // -- updatePasswordAndPrivateKey --

    override suspend fun updatePasswordAndPrivateKey(
        userId: String,
        newPasswordHash: String,
        newPrivateKeyPem: String,
    ): Unit = withContext(Dispatchers.IO) {
        val now = Instant.now().toString()
        db.useConnection { conn ->
            conn.prepareStatement(
                "UPDATE user SET password_hash = ?, private_key_pem = ?, updated_at = ? WHERE id = ?"
            ).use { ps ->
                ps.setString(1, newPasswordHash)
                ps.setString(2, newPrivateKeyPem)
                ps.setString(3, now)
                ps.setString(4, userId)
                ps.executeUpdate()
            }
        }
    }

    // -- updateDisplayName --

    override suspend fun updateDisplayName(userId: String, displayName: String): Unit = withContext(Dispatchers.IO) {
        val now = Instant.now().toString()
        db.useConnection { conn ->
            conn.prepareStatement(
                "UPDATE user SET display_name = ?, updated_at = ? WHERE id = ?"
            ).use { ps ->
                ps.setString(1, displayName)
                ps.setString(2, now)
                ps.setString(3, userId)
                ps.executeUpdate()
            }
        }
    }

    // -- updateLastLoginAt --

    override suspend fun updateLastLoginAt(userId: String): Unit = withContext(Dispatchers.IO) {
        val now = Instant.now().toString()
        db.useConnection { conn ->
            conn.prepareStatement(
                "UPDATE user SET last_login_at = ?, updated_at = ? WHERE id = ?"
            ).use { ps ->
                ps.setString(1, now)
                ps.setString(2, now)
                ps.setString(3, userId)
                ps.executeUpdate()
            }
        }
    }

    // -- hasAnyUser --

    override suspend fun hasAnyUser(): Boolean = withContext(Dispatchers.IO) {
        var has = false
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM user").use { rs ->
                    if (rs.next()) has = rs.getInt(1) > 0
                }
            }
        }
        has
    }

    // -- rowToEntity --

    private fun rowToEntity(rs: java.sql.ResultSet): UserEntity = UserEntity(
        id = rs.getString("id"),
        username = rs.getString("username"),
        displayName = rs.getString("display_name"),
        passwordHash = rs.getString("password_hash"),
        role = rs.getString("role"),
        permissions = rs.getString("permissions"),
        publicKeyPem = rs.getString("public_key_pem"),
        privateKeyPem = rs.getString("private_key_pem"),
        status = rs.getString("status"),
        createdAt = rs.getString("created_at"),
        updatedAt = rs.getString("updated_at"),
        lastLoginAt = rs.getString("last_login_at"),
    )
}
