package com.github.project_fredica.python

import com.github.project_fredica.apputil.createLogger
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CryptoService 集成测试（CS1–CS6）。
 *
 * 需要 pyutil 服务运行。若服务不可达，所有测试自动跳过。
 *
 * 运行：
 *   ./gradlew :shared:jvmTest --tests "com.github.project_fredica.python.CryptoServiceTest"
 */
class CryptoServiceTest {
    private val logger = createLogger()
    private var skipTest = false

    @BeforeTest
    fun setup() {
        runBlocking {
            try {
                PythonUtil.Py314Embed.init()
                PythonUtil.Py314Embed.PyUtilServer.start()
                PythonUtil.Py314Embed.PyUtilServer.requestText(HttpMethod.Get, "/ping")
            } catch (e: Throwable) {
                logger.warn("[CryptoServiceTest] pyutil 不可达，跳过测试: ${e.message}")
                skipTest = true
            }
        }
    }

    // CS1: hashPassword 返回 Argon2id 哈希
    @Test
    fun cs1_hashPassword_returns_argon2_format() = runBlocking {
        if (skipTest) return@runBlocking
        val result = CryptoService.hashPassword("test-password-123")
        assertNull(result.error, "不应有 error: ${result.error}")
        assertNotNull(result.hash)
        assertTrue(result.hash.startsWith("\$argon2id\$"), "应以 \$argon2id\$ 开头")
        Unit
    }

    // CS2: verifyPassword 正确密码 → true
    @Test
    fun cs2_verifyPassword_correct() = runBlocking {
        if (skipTest) return@runBlocking
        val pw = "correct-horse-battery-staple"
        val hashResult = CryptoService.hashPassword(pw)
        assertNotNull(hashResult.hash)

        val verifyResult = CryptoService.verifyPassword(pw, hashResult.hash)
        assertTrue(verifyResult.valid, "正确密码应验证通过")
        Unit
    }

    // CS3: verifyPassword 错误密码 → false
    @Test
    fun cs3_verifyPassword_wrong() = runBlocking {
        if (skipTest) return@runBlocking
        val hashResult = CryptoService.hashPassword("real-password")
        assertNotNull(hashResult.hash)

        val verifyResult = CryptoService.verifyPassword("wrong-password", hashResult.hash)
        assertFalse(verifyResult.valid, "错误密码应验证失败")
        Unit
    }

    // CS4: deriveImk 相同输入 → 相同 IMK
    @Test
    fun cs4_deriveImk_deterministic() = runBlocking {
        if (skipTest) return@runBlocking
        val pw = "my-secret-password"
        val salt = java.util.Base64.getEncoder().encodeToString(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) })

        val r1 = CryptoService.deriveImk(pw, salt)
        val r2 = CryptoService.deriveImk(pw, salt)
        assertNull(r1.error)
        assertNotNull(r1.imkB64)
        assertEquals(r1.imkB64, r2.imkB64, "相同输入应产生相同 IMK")

        val raw = java.util.Base64.getDecoder().decode(r1.imkB64)
        assertEquals(32, raw.size, "IMK 应为 32 字节")
        Unit
    }

    // CS5: generateKeypair 返回 PEM 公钥 + encrypted 私钥
    @Test
    fun cs5_generateKeypair_format() = runBlocking {
        if (skipTest) return@runBlocking
        val imk = java.util.Base64.getEncoder().encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })

        val result = CryptoService.generateKeypair(imk)
        assertNull(result.error, "不应有 error: ${result.error}")
        assertNotNull(result.publicKeyPem)
        assertNotNull(result.encryptedPrivateKey)
        assertTrue(result.publicKeyPem.startsWith("-----BEGIN PUBLIC KEY-----"), "应为 PEM 格式公钥")
        assertTrue(result.encryptedPrivateKey.startsWith("encrypted:"), "应以 encrypted: 前缀")
        Unit
    }

    // CS6: reencryptPrivateKey 往返
    @Test
    fun cs6_reencryptPrivateKey_roundtrip() = runBlocking {
        if (skipTest) return@runBlocking
        val random = java.security.SecureRandom()
        val oldImk = java.util.Base64.getEncoder().encodeToString(ByteArray(32).also { random.nextBytes(it) })
        val newImk = java.util.Base64.getEncoder().encodeToString(ByteArray(32).also { random.nextBytes(it) })

        val kp = CryptoService.generateKeypair(oldImk)
        assertNotNull(kp.encryptedPrivateKey)

        // 旧 IMK → 新 IMK
        val reencrypted = CryptoService.reencryptPrivateKey(kp.encryptedPrivateKey, oldImk, newImk)
        assertNull(reencrypted.error, "重加密不应失败: ${reencrypted.error}")
        assertNotNull(reencrypted.encryptedPrivateKey)
        assertTrue(reencrypted.encryptedPrivateKey.startsWith("encrypted:"))
        assertNotEquals(kp.encryptedPrivateKey, reencrypted.encryptedPrivateKey, "重加密后密文应不同")

        // 新 IMK → 旧 IMK（验证可逆）
        val back = CryptoService.reencryptPrivateKey(reencrypted.encryptedPrivateKey, newImk, oldImk)
        assertNull(back.error, "反向重加密不应失败: ${back.error}")
        assertNotNull(back.encryptedPrivateKey)
        Unit
    }
}
