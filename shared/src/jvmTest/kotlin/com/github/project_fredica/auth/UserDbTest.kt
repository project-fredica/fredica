package com.github.project_fredica.auth

import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class UserDbTest {
    private lateinit var db: Database
    private lateinit var userDb: UserDb
    private lateinit var tmpFile: File

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_user_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        userDb = UserDb(db)
        runBlocking { userDb.initialize() }
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    // U1: createUser + findById 往返
    @Test
    fun u1_createUser_and_findById() = runBlocking {
        val userId = userDb.createUser(
            username = "alice",
            displayName = "Alice",
            passwordHash = "\$argon2id\$v=19\$m=65536,t=3,p=4\$fakehash",
            role = "tenant",
            publicKeyPem = "-----BEGIN PUBLIC KEY-----\nfake\n-----END PUBLIC KEY-----",
            encryptedPrivateKeyPem = "encrypted:fakedata",
        )
        assertNotNull(userId)

        val found = userDb.findById(userId)
        assertNotNull(found)
        assertEquals("alice", found.username)
        assertEquals("Alice", found.displayName)
        assertEquals("tenant", found.role)
        assertEquals("active", found.status)
        assertNotNull(found.createdAt)
        assertNotNull(found.updatedAt)
        assertNull(found.lastLoginAt)
        Unit
    }

    // U2: findByUsername 大小写不敏感
    @Test
    fun u2_findByUsername_case_insensitive() = runBlocking {
        userDb.createUser(username = "Bob", displayName = "Bob", passwordHash = "hash1")

        val found1 = userDb.findByUsername("Bob")
        assertNotNull(found1)
        assertEquals("Bob", found1.username)

        val found2 = userDb.findByUsername("bob")
        assertNotNull(found2)
        assertEquals(found1.id, found2.id)

        val found3 = userDb.findByUsername("BOB")
        assertNotNull(found3)
        assertEquals(found1.id, found3.id)
        Unit
    }

    // U3: createUser 重复 username 抛异常
    @Test
    fun u3_createUser_duplicate_username_throws() = runBlocking {
        userDb.createUser(username = "charlie", displayName = "Charlie", passwordHash = "hash1")
        assertFailsWith<Exception> {
            runBlocking {
                userDb.createUser(username = "charlie", displayName = "Charlie2", passwordHash = "hash2")
            }
        }
        Unit
    }

    // U4: updateStatus
    @Test
    fun u4_updateStatus() = runBlocking {
        val userId = userDb.createUser(username = "dave", displayName = "Dave", passwordHash = "hash1")
        userDb.updateStatus(userId, "disabled")

        val found = userDb.findById(userId)
        assertNotNull(found)
        assertEquals("disabled", found.status)
        Unit
    }

    // U5: updatePassword
    @Test
    fun u5_updatePassword() = runBlocking {
        val userId = userDb.createUser(username = "eve", displayName = "Eve", passwordHash = "old-hash")
        userDb.updatePassword(userId, "new-hash")

        val found = userDb.findById(userId)
        assertNotNull(found)
        assertEquals("new-hash", found.passwordHash)
        Unit
    }

    // U6: updateDisplayName
    @Test
    fun u6_updateDisplayName() = runBlocking {
        val userId = userDb.createUser(username = "frank", displayName = "Frank", passwordHash = "hash1")
        userDb.updateDisplayName(userId, "Franklin")

        val found = userDb.findById(userId)
        assertNotNull(found)
        assertEquals("Franklin", found.displayName)
        Unit
    }

    // U7: updateLastLoginAt
    @Test
    fun u7_updateLastLoginAt() = runBlocking {
        val userId = userDb.createUser(username = "grace", displayName = "Grace", passwordHash = "hash1")
        assertNull(userDb.findById(userId)!!.lastLoginAt)

        userDb.updateLastLoginAt(userId)

        val found = userDb.findById(userId)
        assertNotNull(found)
        assertNotNull(found.lastLoginAt)
        Unit
    }

    // U8: hasAnyUser
    @Test
    fun u8_hasAnyUser() = runBlocking {
        assertFalse(userDb.hasAnyUser())
        userDb.createUser(username = "hank", displayName = "Hank", passwordHash = "hash1")
        assertTrue(userDb.hasAnyUser())
        Unit
    }

    // U9: listAll
    @Test
    fun u9_listAll() = runBlocking {
        assertEquals(0, userDb.listAll().size)
        userDb.createUser(username = "ivan", displayName = "Ivan", passwordHash = "hash1")
        userDb.createUser(username = "judy", displayName = "Judy", passwordHash = "hash2")
        val all = userDb.listAll()
        assertEquals(2, all.size)
        assertEquals("ivan", all[0].username)
        assertEquals("judy", all[1].username)
        Unit
    }
}
