package com.github.project_fredica.auth

import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthSessionDbTest {
    private lateinit var db: Database
    private lateinit var userDb: UserDb
    private lateinit var sessionDb: AuthSessionDb
    private lateinit var tmpFile: File

    private lateinit var testUserId: String

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_session_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        userDb = UserDb(db)
        sessionDb = AuthSessionDb(db)
        runBlocking {
            userDb.initialize()
            sessionDb.initialize()
            testUserId = userDb.createUser(
                username = "testuser",
                displayName = "Test User",
                passwordHash = "hash1",
            )
        }
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    // S1: createSession + findByToken 往返
    @Test
    fun s1_createSession_and_findByToken() = runBlocking {
        val session = sessionDb.createSession(
            userId = testUserId,
            userAgent = "TestAgent/1.0",
            ipAddress = "127.0.0.1",
        )
        assertNotNull(session.sessionId)
        assertNotNull(session.token)
        assertEquals(testUserId, session.userId)
        assertEquals("TestAgent/1.0", session.userAgent)
        assertEquals("127.0.0.1", session.ipAddress)
        assertTrue(session.expiresAt > session.createdAt)

        val found = sessionDb.findByToken(session.token)
        assertNotNull(found)
        assertEquals(session.sessionId, found.sessionId)
        assertEquals(session.token, found.token)
        assertEquals(testUserId, found.userId)
        Unit
    }

    // S2: findByUserId
    @Test
    fun s2_findByUserId() = runBlocking {
        sessionDb.createSession(userId = testUserId)
        sessionDb.createSession(userId = testUserId)

        val sessions = sessionDb.findByUserId(testUserId)
        assertEquals(2, sessions.size)
        // 按 created_at DESC 排序，最新的在前
        assertTrue(sessions[0].createdAt >= sessions[1].createdAt)
        Unit
    }

    // S3: updateLastAccessed
    @Test
    fun s3_updateLastAccessed() = runBlocking {
        val session = sessionDb.createSession(userId = testUserId)
        val originalLastAccessed = session.lastAccessedAt

        // 等待 1 秒确保时间戳不同
        Thread.sleep(1100)
        sessionDb.updateLastAccessed(session.sessionId)

        val found = sessionDb.findByToken(session.token)
        assertNotNull(found)
        assertTrue(found.lastAccessedAt >= originalLastAccessed)
        Unit
    }

    // S4: deleteBySessionId
    @Test
    fun s4_deleteBySessionId() = runBlocking {
        val session = sessionDb.createSession(userId = testUserId)
        assertNotNull(sessionDb.findByToken(session.token))

        sessionDb.deleteBySessionId(session.sessionId)
        assertNull(sessionDb.findByToken(session.token))
        Unit
    }

    // S5: deleteByUserId
    @Test
    fun s5_deleteByUserId() = runBlocking {
        sessionDb.createSession(userId = testUserId)
        sessionDb.createSession(userId = testUserId)
        assertEquals(2, sessionDb.findByUserId(testUserId).size)

        sessionDb.deleteByUserId(testUserId)
        assertEquals(0, sessionDb.findByUserId(testUserId).size)
        Unit
    }

    // S6: deleteByUserIdExcept
    @Test
    fun s6_deleteByUserIdExcept() = runBlocking {
        val s1 = sessionDb.createSession(userId = testUserId)
        sessionDb.createSession(userId = testUserId)
        sessionDb.createSession(userId = testUserId)
        assertEquals(3, sessionDb.findByUserId(testUserId).size)

        sessionDb.deleteByUserIdExcept(testUserId, s1.sessionId)

        val remaining = sessionDb.findByUserId(testUserId)
        assertEquals(1, remaining.size)
        assertEquals(s1.sessionId, remaining[0].sessionId)
        Unit
    }

    // S7: deleteExpired（过期 session 被清理）
    @Test
    fun s7_deleteExpired() = runBlocking {
        // 创建一个正常 session
        val validSession = sessionDb.createSession(userId = testUserId)

        // 手动插入一个已过期的 session（expires_at 在过去）
        val nowSec = System.currentTimeMillis() / 1000L
        db.useConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO auth_session (session_id, token, user_id, created_at, expires_at, last_accessed_at, user_agent, ip_address)
                VALUES (?, ?, ?, ?, ?, ?, '', '')
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, "expired-session-id")
                ps.setString(2, "expired-token")
                ps.setString(3, testUserId)
                ps.setLong(4, nowSec - 86400 * 10)  // 10 天前创建
                ps.setLong(5, nowSec - 1)            // 已过期
                ps.setLong(6, nowSec)                // 最近访问过
                ps.executeUpdate()
            }
        }

        assertEquals(2, sessionDb.findByUserId(testUserId).size)

        sessionDb.deleteExpired()

        val remaining = sessionDb.findByUserId(testUserId)
        assertEquals(1, remaining.size)
        assertEquals(validSession.sessionId, remaining[0].sessionId)
        Unit
    }

    // S8: FIFO 淘汰（超过 MAX_SESSIONS_PER_USER 时删最旧的）
    @Test
    fun s8_fifo_eviction() = runBlocking {
        // 创建 5 个 session（达到上限）
        val sessions = mutableListOf<AuthSessionEntity>()
        for (i in 1..5) {
            sessions.add(sessionDb.createSession(userId = testUserId))
        }
        assertEquals(5, sessionDb.findByUserId(testUserId).size)

        // 创建第 6 个，应该淘汰最旧的
        val newest = sessionDb.createSession(userId = testUserId)
        val remaining = sessionDb.findByUserId(testUserId)
        assertEquals(5, remaining.size)

        // 最旧的 session 应该被删除
        assertNull(sessionDb.findByToken(sessions[0].token))
        // 最新的 session 应该存在
        assertNotNull(sessionDb.findByToken(newest.token))
        Unit
    }
}
