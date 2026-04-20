package com.github.project_fredica.bilibili_account_pool

import com.github.project_fredica.bilibili_account_pool.db.BilibiliAccountPoolDb
import com.github.project_fredica.bilibili_account_pool.model.BilibiliAccount
import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.*

class BilibiliAccountPoolDbTest {
    private lateinit var db: Database
    private lateinit var tmpFile: File
    private lateinit var poolDb: BilibiliAccountPoolDb

    private val now = System.currentTimeMillis() / 1000L

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_account_pool_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        poolDb = BilibiliAccountPoolDb(db)
        runBlocking { poolDb.initialize() }
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    private fun makeAccount(
        id: String = "acct-1",
        label: String = "测试账号",
        isAnonymous: Boolean = false,
        isDefault: Boolean = false,
        sessdata: String = "sess_abc",
        proxy: String = "",
        rateLimitSec: Double = 1.0,
        sortOrder: Int = 0,
    ) = BilibiliAccount(
        id = id,
        label = label,
        isAnonymous = isAnonymous,
        isDefault = isDefault,
        bilibiliSessdata = sessdata,
        bilibiliBiliJct = "jct_abc",
        bilibiliBuvid3 = "buvid3_abc",
        bilibiliBuvid4 = "buvid4_abc",
        bilibiliDedeuserid = "uid_123",
        bilibiliAcTimeValue = "ac_abc",
        bilibiliProxy = proxy,
        rateLimitSec = rateLimitSec,
        sortOrder = sortOrder,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun upsertAll_and_getAll() = runBlocking {
        val accounts = listOf(
            makeAccount(id = "a1", sortOrder = 2),
            makeAccount(id = "a2", sortOrder = 1),
            makeAccount(id = "a3", sortOrder = 0),
        )
        poolDb.upsertAll(accounts)

        val all = poolDb.getAll()
        assertEquals(3, all.size)
        assertEquals(listOf("a3", "a2", "a1"), all.map { it.id })
        Unit
    }

    @Test
    fun upsertAll_idempotent() = runBlocking {
        val account = makeAccount(id = "a1", label = "v1")
        poolDb.upsertAll(listOf(account))
        assertEquals("v1", poolDb.getById("a1")!!.label)

        val updated = account.copy(label = "v2", updatedAt = now + 10)
        poolDb.upsertAll(listOf(updated))
        assertEquals(1, poolDb.getAll().size)
        assertEquals("v2", poolDb.getById("a1")!!.label)
        Unit
    }

    @Test
    fun getById_found_and_not_found() = runBlocking {
        poolDb.upsertAll(listOf(makeAccount(id = "exists")))
        assertNotNull(poolDb.getById("exists"))
        assertNull(poolDb.getById("missing"))
        Unit
    }

    @Test
    fun getById_fields_correct() = runBlocking {
        val acct = makeAccount(
            id = "f1",
            label = "字段测试",
            isAnonymous = true,
            isDefault = true,
            sessdata = "s1",
            proxy = "http://proxy:8080",
            rateLimitSec = 2.5,
            sortOrder = 5,
        )
        poolDb.upsertAll(listOf(acct))

        val loaded = poolDb.getById("f1")!!
        assertEquals("字段测试", loaded.label)
        assertTrue(loaded.isAnonymous)
        assertTrue(loaded.isDefault)
        assertEquals("s1", loaded.bilibiliSessdata)
        assertEquals("jct_abc", loaded.bilibiliBiliJct)
        assertEquals("http://proxy:8080", loaded.bilibiliProxy)
        assertEquals(2.5, loaded.rateLimitSec)
        assertEquals(5, loaded.sortOrder)
        assertEquals(now, loaded.createdAt)
        Unit
    }

    @Test
    fun getDefault_returns_default_account() = runBlocking {
        poolDb.upsertAll(listOf(
            makeAccount(id = "normal", isDefault = false),
            makeAccount(id = "default", isDefault = true),
        ))

        val default = poolDb.getDefault()
        assertNotNull(default)
        assertEquals("default", default.id)
        Unit
    }

    @Test
    fun getDefault_returns_null_when_none() = runBlocking {
        poolDb.upsertAll(listOf(makeAccount(id = "a1", isDefault = false)))
        assertNull(poolDb.getDefault())
        Unit
    }

    @Test
    fun deleteById() = runBlocking {
        poolDb.upsertAll(listOf(
            makeAccount(id = "keep"),
            makeAccount(id = "remove"),
        ))
        poolDb.deleteById("remove")

        assertEquals(1, poolDb.getAll().size)
        assertNull(poolDb.getById("remove"))
        assertNotNull(poolDb.getById("keep"))
        Unit
    }

    @Test
    fun deleteAll() = runBlocking {
        poolDb.upsertAll(listOf(
            makeAccount(id = "a1"),
            makeAccount(id = "a2"),
        ))
        assertEquals(2, poolDb.getAll().size)

        poolDb.deleteAll()
        assertEquals(0, poolDb.getAll().size)
        Unit
    }

    @Test
    fun updateIpCheckResult() = runBlocking {
        poolDb.upsertAll(listOf(makeAccount(id = "ip-test")))

        val checkedAt = now + 100
        poolDb.updateIpCheckResult("ip-test", "1.2.3.4", checkedAt)

        val acct = poolDb.getById("ip-test")!!
        assertEquals("1.2.3.4", acct.lastIp)
        assertEquals(checkedAt, acct.lastIpCheckedAt)
        assertEquals(checkedAt, acct.updatedAt)
        Unit
    }

    @Test
    fun getAll_empty_table() = runBlocking {
        assertEquals(0, poolDb.getAll().size)
        Unit
    }
}
