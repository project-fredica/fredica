package com.github.project_fredica.auth

// =============================================================================
// InviteModels —— 邀请链接系统模型
// =============================================================================
//
// 两种邀请链接，用途不同：
//
//   GuestInviteLink（游客邀请）
//     - 访问后获得 webserver_auth_token，身份为 Guest（只读）
//     - 无使用次数上限，无过期时间，适合长期分享给外部访客
//     - 记录每次访问（GuestInviteVisit），用于统计和审计
//
//   TenantInviteLink（租户邀请）
//     - 访问后可注册新账号，身份为 TenantUser（完整权限）
//     - 有 max_uses 上限和 expires_at 过期时间，防止滥用
//     - 记录每次注册（TenantInviteRegistration），关联到具体用户
//
// path_id 是 URL 中的可读标识（如 /invite/my-team），与内部 UUID id 分离，
// 原因：允许 ROOT 用户自定义易记路径，同时保持内部 ID 稳定。
// =============================================================================

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ==================== 游客邀请链接 ====================

@Serializable
data class GuestInviteLink(
    val id: String,
    @SerialName("path_id") val pathId: String,
    val label: String = "",
    val status: String = "active",
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("visit_count") val visitCount: Int = 0,
)

@Serializable
data class GuestInviteVisit(
    val id: String,
    @SerialName("link_id") val linkId: String,
    @SerialName("ip_address") val ipAddress: String = "",
    @SerialName("user_agent") val userAgent: String = "",
    @SerialName("visited_at") val visitedAt: String,
)

// ==================== 租户邀请链接 ====================

@Serializable
data class TenantInviteLink(
    val id: String,
    @SerialName("path_id") val pathId: String,
    val label: String = "",
    val status: String = "active",
    @SerialName("max_uses") val maxUses: Int,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("used_count") val usedCount: Int = 0,
)

@Serializable
data class TenantInviteRegistration(
    val id: String,
    @SerialName("link_id") val linkId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("ip_address") val ipAddress: String = "",
    @SerialName("user_agent") val userAgent: String = "",
    @SerialName("registered_at") val registeredAt: String,
    val username: String? = null,
    @SerialName("display_name") val displayName: String? = null,
)

// ==================== Repo 接口 ====================

interface GuestInviteLinkRepo {
    suspend fun initialize()
    suspend fun create(pathId: String, label: String, createdBy: String): String
    suspend fun findById(id: String): GuestInviteLink?
    suspend fun findByPathId(pathId: String): GuestInviteLink?
    suspend fun listAll(): List<GuestInviteLink>
    suspend fun updateStatus(id: String, status: String)
    suspend fun updateLabel(id: String, label: String)
    suspend fun delete(id: String)
}

interface GuestInviteVisitRepo {
    suspend fun initialize()
    suspend fun record(linkId: String, ipAddress: String, userAgent: String): String
    suspend fun listByLinkId(linkId: String, limit: Int = 100, offset: Int = 0): List<GuestInviteVisit>
    suspend fun countByLinkId(linkId: String): Int
}

interface TenantInviteLinkRepo {
    suspend fun initialize()
    suspend fun create(pathId: String, label: String, maxUses: Int, expiresAt: String, createdBy: String): String
    suspend fun findById(id: String): TenantInviteLink?
    suspend fun findByPathId(pathId: String): TenantInviteLink?
    suspend fun listAll(): List<TenantInviteLink>
    suspend fun updateStatus(id: String, status: String)
    suspend fun updateLabel(id: String, label: String)
    suspend fun delete(id: String)
    suspend fun isUsable(pathId: String): Boolean
}

interface TenantInviteRegistrationRepo {
    suspend fun initialize()
    suspend fun record(linkId: String, userId: String, ipAddress: String, userAgent: String): String
    suspend fun listByLinkId(linkId: String): List<TenantInviteRegistration>
    suspend fun countByLinkId(linkId: String): Int
}

// ==================== Service 单例 ====================

object GuestInviteLinkService {
    private var _repo: GuestInviteLinkRepo? = null
    val repo: GuestInviteLinkRepo get() = _repo ?: error("GuestInviteLinkService 未初始化")
    fun initialize(repo: GuestInviteLinkRepo) { _repo = repo }
}

object GuestInviteVisitService {
    private var _repo: GuestInviteVisitRepo? = null
    val repo: GuestInviteVisitRepo get() = _repo ?: error("GuestInviteVisitService 未初始化")
    fun initialize(repo: GuestInviteVisitRepo) { _repo = repo }
}

object TenantInviteLinkService {
    private var _repo: TenantInviteLinkRepo? = null
    val repo: TenantInviteLinkRepo get() = _repo ?: error("TenantInviteLinkService 未初始化")
    fun initialize(repo: TenantInviteLinkRepo) { _repo = repo }
}

object TenantInviteRegistrationService {
    private var _repo: TenantInviteRegistrationRepo? = null
    val repo: TenantInviteRegistrationRepo get() = _repo ?: error("TenantInviteRegistrationService 未初始化")
    fun initialize(repo: TenantInviteRegistrationRepo) { _repo = repo }
}
