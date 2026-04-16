package com.github.project_fredica.auth

// =============================================================================
// TenantInviteLinkDb / TenantInviteRegistrationDb —— 租户邀请链接持久化
// =============================================================================
//
// 租户邀请链接的用途：
//   ROOT 用户创建链接（设定 max_uses + expires_at）→ 分享给受信任的人
//   → 对方访问链接注册账号 → 获得 TenantUser 身份（完整权限）
//
// isUsable() 的三重检查：
//   1. status == 'active'：链接未被手动禁用
//   2. now < expires_at：链接未过期
//   3. used_count < max_uses：未达到使用上限
//   三者同时满足才允许注册，防止过期或超限的链接被利用。
//
// used_count 同 GuestInviteLinkDb，通过 LEFT JOIN 实时聚合，不存冗余字段。
//
// delete() 的保护逻辑：
//   有注册记录的链接不允许删除（抛异常），原因：
//   注册记录关联到真实用户账号，删除链接会破坏审计追踪。
//   如需停用，应调用 updateStatus(id, "disabled") 而非 delete()。
// =============================================================================

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

class TenantInviteLinkDb(private val db: Database) : TenantInviteLinkRepo {

    override suspend fun initialize(): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS tenant_invite_link (
                        id          TEXT PRIMARY KEY,
                        path_id     TEXT NOT NULL UNIQUE,
                        label       TEXT NOT NULL DEFAULT '',
                        status      TEXT NOT NULL DEFAULT 'active',
                        max_uses    INTEGER NOT NULL,
                        expires_at  TEXT NOT NULL,
                        created_by  TEXT NOT NULL,
                        created_at  TEXT NOT NULL,
                        updated_at  TEXT NOT NULL,

                        CHECK (length(path_id) >= 3 AND length(path_id) <= 32),
                        CHECK (path_id GLOB '[a-zA-Z0-9_-]*'),
                        CHECK (status IN ('active', 'disabled')),
                        CHECK (max_uses >= 1)
                    )
                    """.trimIndent()
                )
            }
        }
    }

    override suspend fun create(
        pathId: String,
        label: String,
        maxUses: Int,
        expiresAt: String,
        createdBy: String,
    ): String = withContext(Dispatchers.IO) {
        require(pathId.length in 3..32) { "pathId 长度必须在 3-32 之间" }
        require(pathId.matches(Regex("^[a-zA-Z0-9_-]+$"))) { "pathId 只能包含字母、数字、下划线和连字符" }
        require(maxUses >= 1) { "max_uses 必须 >= 1" }
        // 验证 expires_at 是合法的 ISO 时间
        val parsedExpiry = Instant.parse(expiresAt)
        require(parsedExpiry > Instant.now()) { "expires_at 必须是未来时间" }

        val id = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO tenant_invite_link (id, path_id, label, status, max_uses, expires_at, created_by, created_at, updated_at)
                VALUES (?, ?, ?, 'active', ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, pathId)
                ps.setString(3, label)
                ps.setInt(4, maxUses)
                ps.setString(5, expiresAt)
                ps.setString(6, createdBy)
                ps.setString(7, now)
                ps.setString(8, now)
                ps.executeUpdate()
            }
        }
        id
    }

    override suspend fun findById(id: String): TenantInviteLink? = withContext(Dispatchers.IO) {
        var result: TenantInviteLink? = null
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                SELECT l.*, COALESCE(r.cnt, 0) AS used_count
                FROM tenant_invite_link l
                LEFT JOIN (SELECT link_id, COUNT(*) AS cnt FROM tenant_invite_registration GROUP BY link_id) r
                    ON r.link_id = l.id
                WHERE l.id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs ->
                    if (rs.next()) result = rowToLink(rs)
                }
            }
        }
        result
    }

    override suspend fun findByPathId(pathId: String): TenantInviteLink? = withContext(Dispatchers.IO) {
        var result: TenantInviteLink? = null
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                SELECT l.*, COALESCE(r.cnt, 0) AS used_count
                FROM tenant_invite_link l
                LEFT JOIN (SELECT link_id, COUNT(*) AS cnt FROM tenant_invite_registration GROUP BY link_id) r
                    ON r.link_id = l.id
                WHERE l.path_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, pathId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) result = rowToLink(rs)
                }
            }
        }
        result
    }

    override suspend fun listAll(): List<TenantInviteLink> = withContext(Dispatchers.IO) {
        val list = mutableListOf<TenantInviteLink>()
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    """
                    SELECT l.*, COALESCE(r.cnt, 0) AS used_count
                    FROM tenant_invite_link l
                    LEFT JOIN (SELECT link_id, COUNT(*) AS cnt FROM tenant_invite_registration GROUP BY link_id) r
                        ON r.link_id = l.id
                    ORDER BY l.created_at DESC
                    """.trimIndent()
                ).use { rs ->
                    while (rs.next()) list.add(rowToLink(rs))
                }
            }
        }
        list
    }

    override suspend fun updateStatus(id: String, status: String): Unit = withContext(Dispatchers.IO) {
        require(status in listOf("active", "disabled")) { "status 只能是 active 或 disabled" }
        val now = Instant.now().toString()
        db.useConnection { conn ->
            conn.prepareStatement(
                "UPDATE tenant_invite_link SET status = ?, updated_at = ? WHERE id = ?"
            ).use { ps ->
                ps.setString(1, status)
                ps.setString(2, now)
                ps.setString(3, id)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun updateLabel(id: String, label: String): Unit = withContext(Dispatchers.IO) {
        val now = Instant.now().toString()
        db.useConnection { conn ->
            conn.prepareStatement(
                "UPDATE tenant_invite_link SET label = ?, updated_at = ? WHERE id = ?"
            ).use { ps ->
                ps.setString(1, label)
                ps.setString(2, now)
                ps.setString(3, id)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            // 检查是否有注册记录
            var regCount = 0
            conn.prepareStatement(
                "SELECT COUNT(*) FROM tenant_invite_registration WHERE link_id = ?"
            ).use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs ->
                    if (rs.next()) regCount = rs.getInt(1)
                }
            }
            if (regCount > 0) {
                error("该邀请链接已有 $regCount 条注册记录，不可删除")
            }
            conn.prepareStatement("DELETE FROM tenant_invite_link WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun isUsable(pathId: String): Boolean = withContext(Dispatchers.IO) {
        var usable = false
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                SELECT l.id, l.status, l.max_uses, l.expires_at, COALESCE(r.cnt, 0) AS used_count
                FROM tenant_invite_link l
                LEFT JOIN (SELECT link_id, COUNT(*) AS cnt FROM tenant_invite_registration GROUP BY link_id) r
                    ON r.link_id = l.id
                WHERE l.path_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, pathId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        val status = rs.getString("status")
                        val maxUses = rs.getInt("max_uses")
                        val expiresAt = rs.getString("expires_at")
                        val usedCount = rs.getInt("used_count")
                        val now = Instant.now()
                        val expiry = Instant.parse(expiresAt)
                        usable = status == "active" && now < expiry && usedCount < maxUses
                    }
                }
            }
        }
        usable
    }

    private fun rowToLink(rs: ResultSet) = TenantInviteLink(
        id = rs.getString("id"),
        pathId = rs.getString("path_id"),
        label = rs.getString("label"),
        status = rs.getString("status"),
        maxUses = rs.getInt("max_uses"),
        expiresAt = rs.getString("expires_at"),
        createdBy = rs.getString("created_by"),
        createdAt = rs.getString("created_at"),
        updatedAt = rs.getString("updated_at"),
        usedCount = rs.getInt("used_count"),
    )
}

class TenantInviteRegistrationDb(private val db: Database) : TenantInviteRegistrationRepo {

    override suspend fun initialize(): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS tenant_invite_registration (
                        id            TEXT PRIMARY KEY,
                        link_id       TEXT NOT NULL,
                        user_id       TEXT NOT NULL,
                        ip_address    TEXT NOT NULL DEFAULT '',
                        user_agent    TEXT NOT NULL DEFAULT '',
                        registered_at TEXT NOT NULL,

                        FOREIGN KEY (link_id) REFERENCES tenant_invite_link(id),
                        FOREIGN KEY (user_id) REFERENCES user(id)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_tenant_invite_reg_link_id ON tenant_invite_registration(link_id)"
                )
            }
        }
    }

    override suspend fun record(
        linkId: String,
        userId: String,
        ipAddress: String,
        userAgent: String,
    ): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO tenant_invite_registration (id, link_id, user_id, ip_address, user_agent, registered_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, linkId)
                ps.setString(3, userId)
                ps.setString(4, ipAddress)
                ps.setString(5, userAgent)
                ps.setString(6, now)
                ps.executeUpdate()
            }
        }
        id
    }

    override suspend fun listByLinkId(linkId: String): List<TenantInviteRegistration> =
        withContext(Dispatchers.IO) {
            val list = mutableListOf<TenantInviteRegistration>()
            db.useConnection { conn ->
                conn.prepareStatement(
                    """
                    SELECT r.*, u.username, u.display_name
                    FROM tenant_invite_registration r
                    LEFT JOIN user u ON u.id = r.user_id
                    WHERE r.link_id = ?
                    ORDER BY r.registered_at DESC
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, linkId)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) list.add(rowToRegistration(rs))
                    }
                }
            }
            list
        }

    override suspend fun countByLinkId(linkId: String): Int = withContext(Dispatchers.IO) {
        var count = 0
        db.useConnection { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM tenant_invite_registration WHERE link_id = ?"
            ).use { ps ->
                ps.setString(1, linkId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) count = rs.getInt(1)
                }
            }
        }
        count
    }

    private fun rowToRegistration(rs: ResultSet) = TenantInviteRegistration(
        id = rs.getString("id"),
        linkId = rs.getString("link_id"),
        userId = rs.getString("user_id"),
        ipAddress = rs.getString("ip_address"),
        userAgent = rs.getString("user_agent"),
        registeredAt = rs.getString("registered_at"),
        username = rs.getString("username"),
        displayName = rs.getString("display_name"),
    )
}
