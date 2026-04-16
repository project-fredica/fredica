package com.github.project_fredica.auth

// =============================================================================
// AuthModels —— 认证系统核心模型
// =============================================================================
//
// 整体架构：
//   1. 角色体系：GUEST < TENANT < ROOT（ordinal 顺序即权限高低，用于 minRole 比较）
//   2. 数据模型：UserEntity（含密码哈希，仅后端内部）/ UserRecord（脱敏，用于 API 响应）
//   3. 身份标识：AuthIdentity sealed interface，每次请求由 AuthService.resolveIdentity() 解析
//   4. 服务接口：AuthServiceApi 定义在 commonMain，实现在 jvmMain（AuthService.kt）
//      原因：commonMain 不能依赖 JVM 加密库（Argon2/ECDSA），通过接口隔离平台差异
//   5. Holder 模式：XxxService.initialize(impl) 注入实现，测试时可注入 mock
//
// 请求鉴权流程（FredicaApi.jvm.kt handleRoute）：
//   HTTP 请求 → checkAuth() → resolveIdentity() → minRole 检查 → handler(param, RouteContext)
// =============================================================================

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// -- 角色枚举 --
// 注意：ordinal 顺序（GUEST=0, TENANT=1, ROOT=2）直接用于 minRole 权限比较，
// 不可随意调整枚举顺序。

enum class AuthRole {
    GUEST, TENANT, ROOT;

    companion object {
        fun fromString(s: String): AuthRole = when (s.lowercase()) {
            "root" -> ROOT
            "tenant" -> TENANT
            else -> GUEST
        }
    }
}

// -- User 表实体（含敏感字段，仅后端使用） --
// UserEntity 包含 passwordHash / privateKeyPem，绝不能序列化到 API 响应。
// 需要对外暴露时，用 UserRecord.fromEntity() 转换。

@Serializable
data class UserEntity(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("password_hash") val passwordHash: String,
    val role: String = "tenant",
    val permissions: String = "",
    @SerialName("public_key_pem") val publicKeyPem: String = "",
    @SerialName("private_key_pem") val privateKeyPem: String = "",
    val status: String = "active",
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("last_login_at") val lastLoginAt: String? = null,
)

// -- User 公开版本（不含密码哈希等敏感字段，用于 API 响应） --

@Serializable
data class UserRecord(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
    val role: String,
    val permissions: String = "",
    @SerialName("public_key_pem") val publicKeyPem: String = "",
    val status: String = "active",
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("last_login_at") val lastLoginAt: String? = null,
) {
    companion object {
        fun fromEntity(e: UserEntity) = UserRecord(
            id = e.id,
            username = e.username,
            displayName = e.displayName,
            role = e.role,
            permissions = e.permissions,
            publicKeyPem = e.publicKeyPem,
            status = e.status,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt,
            lastLoginAt = e.lastLoginAt,
        )
    }
}

// -- AuthSession 表实体 --

@Serializable
data class AuthSessionEntity(
    @SerialName("session_id") val sessionId: String,
    val token: String,
    @SerialName("user_id") val userId: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("last_accessed_at") val lastAccessedAt: Long,
    @SerialName("user_agent") val userAgent: String = "",
    @SerialName("ip_address") val ipAddress: String = "",
)

// -- AuthIdentity（解析后的身份，用于请求上下文） --
// resolveIdentity() 解析 Bearer token 后返回此类型，注入到 RouteContext.identity。
// 路由 handler 通过 context.identity is AuthIdentity.RootUser 做细粒度权限判断。
// Guest：持有 webserver_auth_token 的外部访客（只读权限）。
// TenantUser / RootUser：持有 fredica_session:<token> 的登录用户。

sealed interface AuthIdentity {
    /** 已认证用户的公共接口（TenantUser / RootUser 共享字段） */
    sealed interface Authenticated : AuthIdentity {
        val userId: String
        val username: String
        val displayName: String
        val role: AuthRole
        val permissions: String
        val sessionId: String
    }

    data object Guest : AuthIdentity

    data class TenantUser(
        override val userId: String,
        override val username: String,
        override val displayName: String,
        override val permissions: String,
        override val sessionId: String,
    ) : Authenticated {
        override val role: AuthRole get() = AuthRole.TENANT
    }

    data class RootUser(
        override val userId: String,
        override val username: String,
        override val displayName: String,
        override val permissions: String,
        override val sessionId: String,
    ) : Authenticated {
        override val role: AuthRole get() = AuthRole.ROOT
    }
}

// -- UserRepo 接口 --

interface UserRepo {
    suspend fun initialize()
    suspend fun createUser(
        username: String,
        displayName: String,
        passwordHash: String,
        role: String = "tenant",
        publicKeyPem: String = "",
        encryptedPrivateKeyPem: String = "",
    ): String

    suspend fun findByUsername(username: String): UserEntity?
    suspend fun findById(userId: String): UserEntity?
    suspend fun listAll(): List<UserEntity>
    suspend fun updateStatus(userId: String, status: String)
    suspend fun updatePassword(userId: String, newPasswordHash: String)
    suspend fun updatePasswordAndPrivateKey(userId: String, newPasswordHash: String, newPrivateKeyPem: String)
    suspend fun updateDisplayName(userId: String, displayName: String)
    suspend fun updateLastLoginAt(userId: String)
    suspend fun hasAnyUser(): Boolean
}

// -- AuthSessionRepo 接口 --

interface AuthSessionRepo {
    suspend fun initialize()
    suspend fun createSession(userId: String, userAgent: String = "", ipAddress: String = ""): AuthSessionEntity
    suspend fun findByToken(token: String): AuthSessionEntity?
    suspend fun findByUserId(userId: String): List<AuthSessionEntity>
    suspend fun updateLastAccessed(sessionId: String)
    suspend fun deleteBySessionId(sessionId: String)
    suspend fun deleteByUserId(userId: String)
    suspend fun deleteByUserIdExcept(userId: String, currentSessionId: String)
    suspend fun deleteExpired()
}

// -- Service 单例 --

object UserService {
    private var _repo: UserRepo? = null

    val repo: UserRepo
        get() = _repo ?: error("UserService 未初始化，请先调用 UserService.initialize()")

    fun initialize(repo: UserRepo) {
        _repo = repo
    }
}

object AuthSessionService {
    private var _repo: AuthSessionRepo? = null

    val repo: AuthSessionRepo
        get() = _repo ?: error("AuthSessionService 未初始化，请先调用 AuthSessionService.initialize()")

    fun initialize(repo: AuthSessionRepo) {
        _repo = repo
    }
}

// -- AuthService 接口（commonMain 路由通过此接口调用，jvmMain 注册实现） --
// 之所以用接口而非直接调用 AuthService：
//   - commonMain 不能依赖 JVM 加密库（Argon2 哈希、ECDSA 密钥对），必须通过接口隔离
//   - 测试时可注入 mock 实现，无需启动真实加密服务（CryptoService 依赖 Python 子进程）

interface AuthServiceApi {
    suspend fun resolveIdentity(authHeader: String?): AuthIdentity?
    suspend fun login(username: String, password: String, userAgent: String = "", ipAddress: String = ""): LoginResult
    suspend fun logout(sessionId: String)
    suspend fun initializeInstance(username: String, password: String): InitResult
    suspend fun isInstanceInitialized(): Boolean
    suspend fun createUser(username: String, displayName: String, password: String): CreateUserResult
    suspend fun changePassword(userId: String, oldPassword: String, newPassword: String, currentSessionId: String): ChangePasswordResult
}

object AuthServiceHolder {
    private var _instance: AuthServiceApi? = null

    val instance: AuthServiceApi
        get() = _instance ?: error("AuthService 未初始化，请先调用 AuthServiceHolder.initialize()")

    fun initialize(impl: AuthServiceApi) {
        _instance = impl
    }
}

// -- LoginRateLimiter 接口 --
// 接口定义在 commonMain，实现（LoginRateLimiter.kt）在 jvmMain，
// 原因同 AuthServiceApi：测试时可注入可控的 mock，不依赖真实时钟。
// check() 返回 null 表示允许，返回正整数表示需等待的秒数。

interface LoginRateLimiterApi {
    fun check(ip: String, username: String): Int?
    fun recordFailure(ip: String, username: String)
    fun clearOnSuccess(ip: String, username: String)
}

object LoginRateLimiterHolder {
    private var _instance: LoginRateLimiterApi? = null

    val instance: LoginRateLimiterApi
        get() = _instance ?: error("LoginRateLimiter 未初始化，请先调用 LoginRateLimiterHolder.initialize()")

    fun initialize(impl: LoginRateLimiterApi) {
        _instance = impl
    }
}

// -- AuthService 结果模型 --
// 所有操作返回结构体而非抛异常，原因：
//   - 业务失败（密码错误、用户名重复）是预期情况，不应走异常路径
//   - 路由 handler 可直接将结果序列化为 JSON 响应，无需额外 try/catch

@Serializable
data class LoginResult(
    val success: Boolean = false,
    val token: String? = null,
    val user: UserRecord? = null,
    val error: String? = null,
)

@Serializable
data class InitResult(
    val success: Boolean = false,
    val token: String? = null,
    val user: UserRecord? = null,
    @SerialName("webserver_auth_token") val webserverAuthToken: String? = null,
    val error: String? = null,
)

@Serializable
data class CreateUserResult(
    val success: Boolean = false,
    val user: UserRecord? = null,
    val error: String? = null,
)

@Serializable
data class ChangePasswordResult(
    val success: Boolean = false,
    val error: String? = null,
)
