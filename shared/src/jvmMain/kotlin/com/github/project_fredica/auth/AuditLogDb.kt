package com.github.project_fredica.auth

// =============================================================================
// AuditLogDb —— 审计日志持久化
// =============================================================================
//
// 三个索引的原因：
//   - idx_audit_log_timestamp：deleteOlderThan() 按时间清理，以及前端按时间排序查询
//   - idx_audit_log_event_type：按事件类型过滤（如只看 LOGIN_FAILED）
//   - idx_audit_log_actor：按操作者查询（如查某用户的所有操作记录）
//
// insert() 的容错设计：
//   - id 为空时自动生成 UUID，方便测试时不传 id
//   - timestamp <= 0 时自动取当前时间，同上
//
// query() 的动态 WHERE 构建：
//   条件参数均为可选，通过 conditions 列表拼接，避免多个重载方法。
//   注意：params 列表顺序必须与 conditions 顺序一致，否则参数绑定错位。
// =============================================================================

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database
import java.util.UUID

class AuditLogDb(private val db: Database) : AuditLogRepo {

    // -- 建表 --

    override suspend fun initialize(): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS audit_log (
                        id              TEXT PRIMARY KEY,
                        timestamp       INTEGER NOT NULL,
                        event_type      TEXT NOT NULL,
                        actor_user_id   TEXT,
                        actor_username  TEXT,
                        target_user_id  TEXT,
                        ip_address      TEXT,
                        user_agent      TEXT,
                        details         TEXT
                    )
                    """.trimIndent()
                )
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_log_timestamp ON audit_log(timestamp)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_log_event_type ON audit_log(event_type)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_log_actor ON audit_log(actor_user_id)")
            }
        }
    }

    // -- insert --

    override suspend fun insert(entry: AuditLogEntry): Unit = withContext(Dispatchers.IO) {
        val id = entry.id.ifBlank { UUID.randomUUID().toString() }
        val ts = if (entry.timestamp > 0) entry.timestamp else System.currentTimeMillis() / 1000L
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO audit_log (id, timestamp, event_type, actor_user_id, actor_username, target_user_id, ip_address, user_agent, details)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, id)
                ps.setLong(2, ts)
                ps.setString(3, entry.eventType)
                ps.setString(4, entry.actorUserId)
                ps.setString(5, entry.actorUsername)
                ps.setString(6, entry.targetUserId)
                ps.setString(7, entry.ipAddress)
                ps.setString(8, entry.userAgent)
                ps.setString(9, entry.details)
                ps.executeUpdate()
            }
        }
    }

    // -- query --

    override suspend fun query(
        eventType: String?,
        actorUserId: String?,
        limit: Int,
        offset: Int,
    ): AuditLogQueryResult = withContext(Dispatchers.IO) {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        if (eventType != null) {
            conditions.add("event_type = ?")
            params.add(eventType)
        }
        if (actorUserId != null) {
            conditions.add("actor_user_id = ?")
            params.add(actorUserId)
        }

        val whereClause = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"

        var total = 0
        val items = mutableListOf<AuditLogEntry>()

        db.useConnection { conn ->
            // count
            conn.prepareStatement("SELECT COUNT(*) FROM audit_log $whereClause").use { ps ->
                params.forEachIndexed { i, v -> ps.setString(i + 1, v as String) }
                ps.executeQuery().use { rs ->
                    if (rs.next()) total = rs.getInt(1)
                }
            }

            // items
            conn.prepareStatement(
                "SELECT * FROM audit_log $whereClause ORDER BY timestamp DESC LIMIT ? OFFSET ?"
            ).use { ps ->
                params.forEachIndexed { i, v -> ps.setString(i + 1, v as String) }
                ps.setInt(params.size + 1, limit)
                ps.setInt(params.size + 2, offset)
                ps.executeQuery().use { rs ->
                    while (rs.next()) items.add(rowToEntry(rs))
                }
            }
        }

        AuditLogQueryResult(items = items, total = total)
    }

    // -- deleteOlderThan --

    override suspend fun deleteOlderThan(timestampSec: Long): Int = withContext(Dispatchers.IO) {
        var deleted = 0
        db.useConnection { conn ->
            conn.prepareStatement(
                "DELETE FROM audit_log WHERE timestamp < ?"
            ).use { ps ->
                ps.setLong(1, timestampSec)
                deleted = ps.executeUpdate()
            }
        }
        deleted
    }

    // -- 内部工具 --

    private fun rowToEntry(rs: java.sql.ResultSet): AuditLogEntry = AuditLogEntry(
        id = rs.getString("id"),
        timestamp = rs.getLong("timestamp"),
        eventType = rs.getString("event_type"),
        actorUserId = rs.getString("actor_user_id"),
        actorUsername = rs.getString("actor_username"),
        targetUserId = rs.getString("target_user_id"),
        ipAddress = rs.getString("ip_address"),
        userAgent = rs.getString("user_agent"),
        details = rs.getString("details"),
    )
}
