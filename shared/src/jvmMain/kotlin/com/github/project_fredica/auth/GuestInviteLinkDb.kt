package com.github.project_fredica.auth

// =============================================================================
// GuestInviteLinkDb / GuestInviteVisitDb —— 游客邀请链接持久化
// =============================================================================
//
// 游客邀请链接的用途：
//   ROOT 用户创建链接 → 分享给外部访客 → 访客访问链接获得 webserver_auth_token
//   → 以 Guest 身份调用只读 API（如查看字幕、播放视频）
//
// visit_count 的实现方式：
//   不在 guest_invite_link 表存计数字段，而是通过 LEFT JOIN guest_invite_visit
//   实时聚合。原因：避免并发更新计数器的竞态问题，且访问量不大，聚合开销可接受。
//
// delete() 的级联逻辑：
//   SQLite 外键默认不级联删除，所以手动先删 guest_invite_visit 再删链接。
//   与 TenantInviteLinkDb.delete() 不同——游客访问记录可以随链接一起删除，
//   但租户注册记录关联到真实用户，不允许删除有注册记录的链接。
// =============================================================================

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

class GuestInviteLinkDb(private val db: Database) : GuestInviteLinkRepo {

    override suspend fun initialize(): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS guest_invite_link (
                        id          TEXT PRIMARY KEY,
                        path_id     TEXT NOT NULL UNIQUE,
                        label       TEXT NOT NULL DEFAULT '',
                        status      TEXT NOT NULL DEFAULT 'active',
                        created_by  TEXT NOT NULL,
                        created_at  TEXT NOT NULL,
                        updated_at  TEXT NOT NULL,

                        CHECK (length(path_id) >= 3 AND length(path_id) <= 32),
                        CHECK (path_id GLOB '[a-zA-Z0-9_-]*'),
                        CHECK (status IN ('active', 'disabled'))
                    )
                    """.trimIndent()
                )
            }
        }
    }

    override suspend fun create(pathId: String, label: String, createdBy: String): String =
        withContext(Dispatchers.IO) {
            require(pathId.length in 3..32) { "pathId 长度必须在 3-32 之间" }
            require(pathId.matches(Regex("^[a-zA-Z0-9_-]+$"))) { "pathId 只能包含字母、数字、下划线和连字符" }
            val id = UUID.randomUUID().toString()
            val now = Instant.now().toString()
            db.useConnection { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO guest_invite_link (id, path_id, label, status, created_by, created_at, updated_at)
                    VALUES (?, ?, ?, 'active', ?, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, id)
                    ps.setString(2, pathId)
                    ps.setString(3, label)
                    ps.setString(4, createdBy)
                    ps.setString(5, now)
                    ps.setString(6, now)
                    ps.executeUpdate()
                }
            }
            id
        }

    override suspend fun findById(id: String): GuestInviteLink? = withContext(Dispatchers.IO) {
        var result: GuestInviteLink? = null
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                SELECT l.*, COALESCE(v.cnt, 0) AS visit_count
                FROM guest_invite_link l
                LEFT JOIN (SELECT link_id, COUNT(*) AS cnt FROM guest_invite_visit GROUP BY link_id) v
                    ON v.link_id = l.id
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

    override suspend fun findByPathId(pathId: String): GuestInviteLink? = withContext(Dispatchers.IO) {
        var result: GuestInviteLink? = null
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                SELECT l.*, COALESCE(v.cnt, 0) AS visit_count
                FROM guest_invite_link l
                LEFT JOIN (SELECT link_id, COUNT(*) AS cnt FROM guest_invite_visit GROUP BY link_id) v
                    ON v.link_id = l.id
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

    override suspend fun listAll(): List<GuestInviteLink> = withContext(Dispatchers.IO) {
        val list = mutableListOf<GuestInviteLink>()
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    """
                    SELECT l.*, COALESCE(v.cnt, 0) AS visit_count
                    FROM guest_invite_link l
                    LEFT JOIN (SELECT link_id, COUNT(*) AS cnt FROM guest_invite_visit GROUP BY link_id) v
                        ON v.link_id = l.id
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
                "UPDATE guest_invite_link SET status = ?, updated_at = ? WHERE id = ?"
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
                "UPDATE guest_invite_link SET label = ?, updated_at = ? WHERE id = ?"
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
            // 级联删除访问记录
            conn.prepareStatement("DELETE FROM guest_invite_visit WHERE link_id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM guest_invite_link WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
        }
    }

    private fun rowToLink(rs: ResultSet) = GuestInviteLink(
        id = rs.getString("id"),
        pathId = rs.getString("path_id"),
        label = rs.getString("label"),
        status = rs.getString("status"),
        createdBy = rs.getString("created_by"),
        createdAt = rs.getString("created_at"),
        updatedAt = rs.getString("updated_at"),
        visitCount = rs.getInt("visit_count"),
    )
}

class GuestInviteVisitDb(private val db: Database) : GuestInviteVisitRepo {

    override suspend fun initialize(): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS guest_invite_visit (
                        id          TEXT PRIMARY KEY,
                        link_id     TEXT NOT NULL,
                        ip_address  TEXT NOT NULL DEFAULT '',
                        user_agent  TEXT NOT NULL DEFAULT '',
                        visited_at  TEXT NOT NULL,

                        FOREIGN KEY (link_id) REFERENCES guest_invite_link(id)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_guest_invite_visit_link_id ON guest_invite_visit(link_id)"
                )
            }
        }
    }

    override suspend fun record(linkId: String, ipAddress: String, userAgent: String): String =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val now = Instant.now().toString()
            db.useConnection { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO guest_invite_visit (id, link_id, ip_address, user_agent, visited_at)
                    VALUES (?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, id)
                    ps.setString(2, linkId)
                    ps.setString(3, ipAddress)
                    ps.setString(4, userAgent)
                    ps.setString(5, now)
                    ps.executeUpdate()
                }
            }
            id
        }

    override suspend fun listByLinkId(linkId: String, limit: Int, offset: Int): List<GuestInviteVisit> =
        withContext(Dispatchers.IO) {
            val list = mutableListOf<GuestInviteVisit>()
            db.useConnection { conn ->
                conn.prepareStatement(
                    """
                    SELECT * FROM guest_invite_visit
                    WHERE link_id = ?
                    ORDER BY visited_at DESC
                    LIMIT ? OFFSET ?
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, linkId)
                    ps.setInt(2, limit)
                    ps.setInt(3, offset)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) list.add(rowToVisit(rs))
                    }
                }
            }
            list
        }

    override suspend fun countByLinkId(linkId: String): Int = withContext(Dispatchers.IO) {
        var count = 0
        db.useConnection { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM guest_invite_visit WHERE link_id = ?"
            ).use { ps ->
                ps.setString(1, linkId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) count = rs.getInt(1)
                }
            }
        }
        count
    }

    private fun rowToVisit(rs: ResultSet) = GuestInviteVisit(
        id = rs.getString("id"),
        linkId = rs.getString("link_id"),
        ipAddress = rs.getString("ip_address"),
        userAgent = rs.getString("user_agent"),
        visitedAt = rs.getString("visited_at"),
    )
}
