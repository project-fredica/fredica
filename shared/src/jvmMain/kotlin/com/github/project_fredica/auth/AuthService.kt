package com.github.project_fredica.auth

// =============================================================================
// AuthService —— 认证业务逻辑实现（jvmMain）
// =============================================================================
//
// 实现 AuthServiceApi 接口，依赖 JVM 专属的 CryptoService（Python 子进程）。
// 之所以放在 jvmMain 而非 commonMain：Argon2 密码哈希和 ECDSA 密钥对生成
// 均通过 Python CryptoService 完成，commonMain 无法直接调用。
//
// Token 格式：
//   - 登录 session：Bearer fredica_session:<base64-token>
//     → resolveIdentity() 查 auth_session 表 → 查 user 表 → TenantUser/RootUser
//   - 游客访问：Bearer <webserver_auth_token>（UUID 格式）
//     → 匹配 WebserverAuthTokenCache → Guest
//
// 密钥体系（IMK = Identity Master Key）：
//   - 每个实例初始化时生成 salt_imk（存 AppConfig）
//   - 用户密码 + salt_imk → PBKDF2 派生 IMK → 加密用户私钥
//   - 修改密码时需用旧 IMK 解密、新 IMK 重加密私钥（reencryptPrivateKey）
//   - 目的：私钥由用户密码保护，服务端无法在不知道密码的情况下解密
// =============================================================================

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.python.CryptoService
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

object AuthService : AuthServiceApi {
    private val logger = createLogger()

    // -- resolveIdentity --

    override suspend fun resolveIdentity(authHeader: String?): AuthIdentity? {
        if (authHeader.isNullOrBlank() || !authHeader.startsWith("Bearer ")) return null

        val token = authHeader.removePrefix("Bearer ").trim()
        if (token.isBlank()) return null

        // fredica_session:<token> → 查 session → 查 user → TenantUser/RootUser
        if (token.startsWith("fredica_session:")) {
            val sessionToken = token.removePrefix("fredica_session:")
            if (sessionToken.isBlank()) return null

            val session = AuthSessionService.repo.findByToken(sessionToken) ?: return null

            // 检查过期
            val nowSec = System.currentTimeMillis() / 1000L
            if (session.expiresAt < nowSec) {
                logger.debug("[AuthService] session expired: ${session.sessionId}")
                return null
            }

            val user = UserService.repo.findById(session.userId) ?: return null

            // 检查用户状态
            if (user.status != "active") {
                logger.debug("[AuthService] user disabled: ${user.username}")
                return null
            }

            // 更新最后访问时间（fire-and-forget 语义，不阻塞请求）
            try {
                AuthSessionService.repo.updateLastAccessed(session.sessionId)
            } catch (e: Throwable) {
                logger.debug("[AuthService] updateLastAccessed failed: ${e.message}")
            }

            val role = AuthRole.fromString(user.role)
            return when (role) {
                AuthRole.ROOT -> AuthIdentity.RootUser(
                    userId = user.id,
                    username = user.username,
                    displayName = user.displayName,
                    permissions = user.permissions,
                    sessionId = session.sessionId,
                )
                else -> AuthIdentity.TenantUser(
                    userId = user.id,
                    username = user.username,
                    displayName = user.displayName,
                    permissions = user.permissions,
                    sessionId = session.sessionId,
                )
            }
        }

        // 非 session token → 匹配 webserver_auth_token → Guest
        val cachedToken = WebserverAuthTokenCache.get()
        if (cachedToken.isNotBlank() && token == cachedToken) {
            return AuthIdentity.Guest
        }

        return null
    }

    // -- login --

    override suspend fun login(
        username: String,
        password: String,
        userAgent: String,
        ipAddress: String,
    ): LoginResult {
        val user = UserService.repo.findByUsername(username)
            ?: return LoginResult(error = "用户名或密码错误")

        if (user.status != "active") {
            return LoginResult(error = "账户已被禁用")
        }

        val verifyResult = CryptoService.verifyPassword(password, user.passwordHash)
        if (verifyResult.error != null) {
            logger.debug("[AuthService] verifyPassword error: ${verifyResult.error}")
            return LoginResult(error = "密码验证失败")
        }
        if (!verifyResult.valid) {
            return LoginResult(error = "用户名或密码错误")
        }

        // 创建 session
        val session = AuthSessionService.repo.createSession(
            userId = user.id,
            userAgent = userAgent,
            ipAddress = ipAddress,
        )

        // 更新最后登录时间
        UserService.repo.updateLastLoginAt(user.id)

        return LoginResult(
            success = true,
            token = "fredica_session:${session.token}",
            user = UserRecord.fromEntity(user),
        )
    }

    // -- logout --

    override suspend fun logout(sessionId: String) {
        AuthSessionService.repo.deleteBySessionId(sessionId)
    }

    // -- initializeInstance --

    override suspend fun initializeInstance(
        username: String,
        password: String,
    ): InitResult {
        // 检查是否已初始化
        if (isInstanceInitialized()) {
            return InitResult(error = "实例已初始化")
        }

        // 哈希密码
        val hashResult = CryptoService.hashPassword(password)
        if (hashResult.error != null || hashResult.hash == null) {
            return InitResult(error = "密码哈希失败: ${hashResult.error}")
        }

        // 生成 salt_imk
        val saltImkBytes = ByteArray(32)
        SecureRandom().nextBytes(saltImkBytes)
        val saltImkB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(saltImkBytes)

        // 生成 salt_auth
        val saltAuthBytes = ByteArray(32)
        SecureRandom().nextBytes(saltAuthBytes)
        val saltAuthB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(saltAuthBytes)

        // 派生 IMK
        val imkResult = CryptoService.deriveImk(password, saltImkB64)
        if (imkResult.error != null || imkResult.imkB64 == null) {
            return InitResult(error = "IMK 派生失败: ${imkResult.error}")
        }

        // 生成密钥对
        val keypairResult = CryptoService.generateKeypair(imkResult.imkB64)
        if (keypairResult.error != null || keypairResult.publicKeyPem == null) {
            return InitResult(error = "密钥对生成失败: ${keypairResult.error}")
        }

        // 创建 root 用户
        val userId = UserService.repo.createUser(
            username = username,
            displayName = username,
            passwordHash = hashResult.hash,
            role = "root",
            publicKeyPem = keypairResult.publicKeyPem,
            encryptedPrivateKeyPem = keypairResult.encryptedPrivateKey ?: "",
        )

        // 生成 webserver_auth_token
        val webserverAuthToken = UUID.randomUUID().toString()

        // 更新 AppConfig
        val config = AppConfigService.repo.getConfig()
        AppConfigService.repo.updateConfig(
            config.copy(
                webserverAuthToken = webserverAuthToken,
                instanceInitialized = "true",
                saltImkB64 = saltImkB64,
                saltAuthB64 = saltAuthB64,
            )
        )
        // 创建 session
        val session = AuthSessionService.repo.createSession(userId = userId)

        // 更新最后登录时间
        UserService.repo.updateLastLoginAt(userId)

        val user = UserService.repo.findById(userId)!!

        return InitResult(
            success = true,
            token = "fredica_session:${session.token}",
            user = UserRecord.fromEntity(user),
            webserverAuthToken = webserverAuthToken,
        )
    }

    // -- isInstanceInitialized --

    override suspend fun isInstanceInitialized(): Boolean {
        val config = AppConfigService.repo.getConfig()
        return config.instanceInitialized == "true"
    }

    // -- createUser --

    override suspend fun createUser(
        username: String,
        displayName: String,
        password: String,
    ): CreateUserResult {
        // 检查用户名唯一性
        if (UserService.repo.findByUsername(username) != null) {
            return CreateUserResult(error = "用户名已存在")
        }

        // 哈希密码
        val hashResult = CryptoService.hashPassword(password)
        if (hashResult.error != null || hashResult.hash == null) {
            return CreateUserResult(error = "密码哈希失败: ${hashResult.error}")
        }

        // 读取 salt_imk
        val config = AppConfigService.repo.getConfig()
        val saltImkB64 = config.saltImkB64
        if (saltImkB64.isBlank()) {
            return CreateUserResult(error = "实例未初始化（缺少 salt_imk）")
        }

        // 派生 IMK
        val imkResult = CryptoService.deriveImk(password, saltImkB64)
        if (imkResult.error != null || imkResult.imkB64 == null) {
            return CreateUserResult(error = "IMK 派生失败: ${imkResult.error}")
        }

        // 生成密钥对
        val keypairResult = CryptoService.generateKeypair(imkResult.imkB64)
        if (keypairResult.error != null || keypairResult.publicKeyPem == null) {
            return CreateUserResult(error = "密钥对生成失败: ${keypairResult.error}")
        }

        // 创建用户
        val userId = UserService.repo.createUser(
            username = username,
            displayName = displayName,
            passwordHash = hashResult.hash,
            role = "tenant",
            publicKeyPem = keypairResult.publicKeyPem,
            encryptedPrivateKeyPem = keypairResult.encryptedPrivateKey ?: "",
        )

        val user = UserService.repo.findById(userId)!!
        return CreateUserResult(
            success = true,
            user = UserRecord.fromEntity(user),
        )
    }

    // -- changePassword --

    override suspend fun changePassword(
        userId: String,
        oldPassword: String,
        newPassword: String,
        currentSessionId: String,
    ): ChangePasswordResult {
        val user = UserService.repo.findById(userId)
            ?: return ChangePasswordResult(error = "用户不存在")

        // 验证旧密码
        val verifyResult = CryptoService.verifyPassword(oldPassword, user.passwordHash)
        if (verifyResult.error != null) {
            logger.debug("[AuthService] verifyPassword error: ${verifyResult.error}")
            return ChangePasswordResult(error = "密码验证失败")
        }
        if (!verifyResult.valid) {
            return ChangePasswordResult(error = "旧密码错误")
        }

        // 哈希新密码
        val hashResult = CryptoService.hashPassword(newPassword)
        if (hashResult.error != null || hashResult.hash == null) {
            return ChangePasswordResult(error = "密码哈希失败: ${hashResult.error}")
        }

        // 重加密私钥
        val config = AppConfigService.repo.getConfig()
        val saltImkB64 = config.saltImkB64
        if (saltImkB64.isNotBlank() && user.privateKeyPem.isNotBlank()) {
            val oldImk = CryptoService.deriveImk(oldPassword, saltImkB64)
            val newImk = CryptoService.deriveImk(newPassword, saltImkB64)
            if (oldImk.imkB64 != null && newImk.imkB64 != null) {
                val reencrypted = CryptoService.reencryptPrivateKey(
                    user.privateKeyPem, oldImk.imkB64, newImk.imkB64
                )
                if (reencrypted.error != null) {
                    return ChangePasswordResult(error = "私钥重加密失败: ${reencrypted.error}")
                }
                UserService.repo.updatePasswordAndPrivateKey(
                    userId, hashResult.hash, reencrypted.encryptedPrivateKey ?: ""
                )
            } else {
                UserService.repo.updatePassword(userId, hashResult.hash)
            }
        } else {
            UserService.repo.updatePassword(userId, hashResult.hash)
        }

        // 销毁其他 session（保留当前）
        AuthSessionService.repo.deleteByUserIdExcept(userId, currentSessionId)

        return ChangePasswordResult(success = true)
    }
}
