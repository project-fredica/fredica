package com.github.project_fredica.auth

// =============================================================================
// AuditLog —— 认证审计日志
// =============================================================================
//
// 记录认证相关的关键事件，用于安全审计和问题排查。
//
// 设计决策：
//   - 只记录"谁对谁做了什么"，不记录密码等敏感内容
//   - actor（操作者）和 target（被操作对象）分开存储，方便按维度查询
//   - timestamp 用 Unix 秒（INTEGER），便于范围查询和 deleteOlderThan 清理
//   - details 字段存 JSON 字符串，保留扩展空间（如记录旧值/新值）
//
// event_type 枚举值：
//   LOGIN_SUCCESS / LOGIN_FAILED / LOGOUT
//   USER_CREATED / USER_DISABLED / USER_ENABLED
//   PASSWORD_CHANGED / DISPLAY_NAME_CHANGED
//   INSTANCE_INITIALIZED
// =============================================================================

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuditLogEntry(
    val id: String,
    val timestamp: Long,
    @SerialName("event_type") val eventType: String,
    @SerialName("actor_user_id") val actorUserId: String? = null,
    @SerialName("actor_username") val actorUsername: String? = null,
    @SerialName("target_user_id") val targetUserId: String? = null,
    @SerialName("ip_address") val ipAddress: String? = null,
    @SerialName("user_agent") val userAgent: String? = null,
    val details: String? = null,
)

@Serializable
data class AuditLogQueryResult(
    val items: List<AuditLogEntry>,
    val total: Int,
)

interface AuditLogRepo {
    suspend fun initialize()

    suspend fun insert(entry: AuditLogEntry)

    suspend fun query(
        eventType: String? = null,
        actorUserId: String? = null,
        limit: Int = 100,
        offset: Int = 0,
    ): AuditLogQueryResult

    suspend fun deleteOlderThan(timestampSec: Long): Int
}

object AuditLogService {
    private var _repo: AuditLogRepo? = null

    val repo: AuditLogRepo
        get() = _repo ?: error("AuditLogService 未初始化，请先调用 AuditLogService.initialize()")

    fun initialize(repo: AuditLogRepo) {
        _repo = repo
    }
}
