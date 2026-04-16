package com.github.project_fredica.python

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJsonModel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 密码学服务封装，调用 Python pyutil 的 /crypto/ 端点。
 *
 * 提供 Argon2id 密码哈希、IMK 派生、Ed25519 密钥对生成、私钥重加密等操作。
 */
object CryptoService {
    private val logger = createLogger()

    private const val TIMEOUT_MS = 30_000L

    // -- 响应模型 --

    @Serializable
    data class HashPasswordResult(
        @SerialName("hash") val hash: String? = null,
        @SerialName("error") val error: String? = null,
    )

    @Serializable
    data class VerifyPasswordResult(
        @SerialName("valid") val valid: Boolean = false,
        @SerialName("error") val error: String? = null,
    )

    @Serializable
    data class DeriveImkResult(
        @SerialName("imk_b64") val imkB64: String? = null,
        @SerialName("error") val error: String? = null,
    )

    @Serializable
    data class KeypairResult(
        @SerialName("public_key_pem") val publicKeyPem: String? = null,
        @SerialName("encrypted_private_key") val encryptedPrivateKey: String? = null,
        @SerialName("error") val error: String? = null,
    )

    @Serializable
    data class ReencryptResult(
        @SerialName("encrypted_private_key") val encryptedPrivateKey: String? = null,
        @SerialName("error") val error: String? = null,
    )

    // -- 公开方法 --

    suspend fun hashPassword(password: String): HashPasswordResult {
        logger.debug("[CryptoService] hashPassword: len=${password.length}")
        val body = buildJsonObject { put("password", password) }.toString()
        val resp = FredicaApi.PyUtil.post("/crypto/hash-password/", body, TIMEOUT_MS)
        return resp.loadJsonModel<HashPasswordResult>().getOrThrow()
    }

    suspend fun verifyPassword(password: String, hash: String): VerifyPasswordResult {
        logger.debug("[CryptoService] verifyPassword")
        val body = buildJsonObject {
            put("password", password)
            put("hash", hash)
        }.toString()
        val resp = FredicaApi.PyUtil.post("/crypto/verify-password/", body, TIMEOUT_MS)
        return resp.loadJsonModel<VerifyPasswordResult>().getOrThrow()
    }

    suspend fun deriveImk(password: String, saltImkB64: String): DeriveImkResult {
        logger.debug("[CryptoService] deriveImk: saltLen=${saltImkB64.length}")
        val body = buildJsonObject {
            put("password", password)
            put("salt_imk_b64", saltImkB64)
        }.toString()
        val resp = FredicaApi.PyUtil.post("/crypto/derive-imk/", body, TIMEOUT_MS)
        return resp.loadJsonModel<DeriveImkResult>().getOrThrow()
    }

    suspend fun generateKeypair(imkB64: String): KeypairResult {
        logger.debug("[CryptoService] generateKeypair")
        val body = buildJsonObject { put("imk_b64", imkB64) }.toString()
        val resp = FredicaApi.PyUtil.post("/crypto/generate-keypair/", body, TIMEOUT_MS)
        return resp.loadJsonModel<KeypairResult>().getOrThrow()
    }

    suspend fun reencryptPrivateKey(
        encryptedPrivateKey: String,
        oldImkB64: String,
        newImkB64: String,
    ): ReencryptResult {
        logger.debug("[CryptoService] reencryptPrivateKey")
        val body = buildJsonObject {
            put("encrypted_private_key", encryptedPrivateKey)
            put("old_imk_b64", oldImkB64)
            put("new_imk_b64", newImkB64)
        }.toString()
        val resp = FredicaApi.PyUtil.post("/crypto/reencrypt-private-key/", body, TIMEOUT_MS)
        return resp.loadJsonModel<ReencryptResult>().getOrThrow()
    }
}
