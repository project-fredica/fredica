package com.github.project_fredica.db

// =============================================================================
// PromptTemplateDbTest —— PromptTemplateDb 单元测试
// =============================================================================
//
// 测试隔离：每个测试方法使用独立的临时 SQLite 文件，@AfterTest 中不需要手动清理
// （File.deleteOnExit() 已注册，JVM 退出时自动删除）。
//
// 注意：不使用 :memory:，原因同 TaskDb 注释：
//   ktorm 连接池每次获取新连接，内存库的各连接是相互独立的数据库实例。
// =============================================================================

import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptTemplateDbTest {

    private lateinit var db: PromptTemplateDb

    @BeforeTest
    fun setup() = runBlocking {
        val tmpFile = File.createTempFile("prompt_template_test_", ".db")
            .also { it.deleteOnExit() }
        val database = Database.connect(
            url = "jdbc:sqlite:${tmpFile.absolutePath}",
            driver = "org.sqlite.JDBC",
        )
        db = PromptTemplateDb(database)
        db.initialize()
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private fun makeTemplate(
        id: String = "tpl-${System.nanoTime()}",
        name: String = "测试模板",
        category: String = "weben_extract",
        scriptCode: String = "async function main() { return 'hello' }",
        schemaTarget: String = "weben_v1",
        basedOnTemplateId: String? = null,
    ) = PromptTemplate(
        id = id,
        name = name,
        description = "描述",
        category = category,
        sourceType = "user",
        scriptLanguage = "javascript",
        scriptCode = scriptCode,
        schemaTarget = schemaTarget,
        basedOnTemplateId = basedOnTemplateId,
        createdAt = 1000L,
        updatedAt = 2000L,
    )

    // ── 基本 CRUD ─────────────────────────────────────────────────────────────

    @Test
    fun `save and getById returns correct template`() = runBlocking {
        val tpl = makeTemplate(id = "tpl-1", name = "模板A")
        db.save(tpl)

        val found = db.getById("tpl-1")
        assertNotNull(found)
        assertEquals("tpl-1", found.id)
        assertEquals("模板A", found.name)
        assertEquals("weben_extract", found.category)
        assertEquals("async function main() { return 'hello' }", found.scriptCode)
        assertEquals("weben_v1", found.schemaTarget)
        assertEquals("user", found.sourceType)
        Unit
    }

    @Test
    fun `getById returns null for nonexistent id`() = runBlocking {
        assertNull(db.getById("no-such-id"))
        Unit
    }

    @Test
    fun `save is upsert - overwrites existing record`() = runBlocking {
        val original = makeTemplate(id = "tpl-upsert", name = "原名称", scriptCode = "return 'v1'")
        db.save(original)

        val updated = original.copy(name = "新名称", scriptCode = "return 'v2'", updatedAt = 9999L)
        db.save(updated)

        val found = db.getById("tpl-upsert")
        assertNotNull(found)
        assertEquals("新名称", found.name)
        assertEquals("return 'v2'", found.scriptCode)
        assertEquals(9999L, found.updatedAt)
        // created_at 不应被覆盖为新值（save 直接使用传入值，调用方负责保留）
        assertEquals(1000L, found.createdAt)
        Unit
    }

    @Test
    fun `save forces sourceType to user`() = runBlocking {
        // 即使调用方传入 source_type = "system"，save() 也强制设为 "user"
        val tampered = makeTemplate(id = "tpl-force").copy(sourceType = "system")
        val saved = db.save(tampered)
        assertEquals("user", saved.sourceType)

        val found = db.getById("tpl-force")
        assertNotNull(found)
        assertEquals("user", found.sourceType)
        Unit
    }

    // ── 列表查询 ──────────────────────────────────────────────────────────────

    @Test
    fun `listUserTemplates returns all user templates ordered by updatedAt desc`() = runBlocking {
        db.save(makeTemplate(id = "tpl-old").copy(updatedAt = 100L))
        db.save(makeTemplate(id = "tpl-new").copy(updatedAt = 999L))
        db.save(makeTemplate(id = "tpl-mid").copy(updatedAt = 500L))

        val list = db.listUserTemplates()
        assertEquals(3, list.size)
        assertEquals("tpl-new", list[0].id)
        assertEquals("tpl-mid", list[1].id)
        assertEquals("tpl-old", list[2].id)
        Unit
    }

    @Test
    fun `listUserTemplates returns empty list when no templates`() = runBlocking {
        val list = db.listUserTemplates()
        assertTrue(list.isEmpty())
        Unit
    }

    @Test
    fun `listUserTemplatesByCategory filters correctly`() = runBlocking {
        db.save(makeTemplate(id = "tpl-a1", category = "cat_a"))
        db.save(makeTemplate(id = "tpl-a2", category = "cat_a"))
        db.save(makeTemplate(id = "tpl-b1", category = "cat_b"))

        val catA = db.listUserTemplatesByCategory("cat_a")
        assertEquals(2, catA.size)
        assertTrue(catA.all { it.category == "cat_a" })

        val catB = db.listUserTemplatesByCategory("cat_b")
        assertEquals(1, catB.size)

        val catC = db.listUserTemplatesByCategory("cat_c")
        assertTrue(catC.isEmpty())
        Unit
    }

    // ── 删除 ──────────────────────────────────────────────────────────────────

    @Test
    fun `delete removes existing user template and returns true`() = runBlocking {
        db.save(makeTemplate(id = "tpl-del"))

        val result = db.delete("tpl-del")
        assertTrue(result)
        assertNull(db.getById("tpl-del"))
        Unit
    }

    @Test
    fun `delete returns false for nonexistent id`() = runBlocking {
        val result = db.delete("tpl-nonexistent")
        assertFalse(result)
        Unit
    }

    @Test
    fun `delete refuses to delete system template`() = runBlocking {
        // 直接通过 JDBC 写入一条 source_type='system' 的行（绕过 save() 的强制转换）
        val database = db
        injectSystemTemplate(database, "sys_test")

        val result = db.delete("sys_test")
        assertFalse(result, "系统模板不应被删除")
        assertNotNull(db.getById("sys_test"), "系统模板删除被拒绝后应仍存在")
        Unit
    }

    // ── basedOnTemplateId ────────────────────────────────────────────────────

    @Test
    fun `basedOnTemplateId is persisted and retrieved correctly`() = runBlocking {
        val tpl = makeTemplate(id = "tpl-child", basedOnTemplateId = "sys_parent")
        db.save(tpl)

        val found = db.getById("tpl-child")
        assertNotNull(found)
        assertEquals("sys_parent", found.basedOnTemplateId)
        Unit
    }

    @Test
    fun `basedOnTemplateId nullable is stored as null`() = runBlocking {
        val tpl = makeTemplate(id = "tpl-no-parent", basedOnTemplateId = null)
        db.save(tpl)

        val found = db.getById("tpl-no-parent")
        assertNotNull(found)
        assertNull(found.basedOnTemplateId)
        Unit
    }
}

// ── 测试辅助：直接注入 source_type='system' 行（不经过 DB 层检查） ──────────────

private fun injectSystemTemplate(db: PromptTemplateDb, id: String) {
    // 利用反射取出内部 Database 实例，插入一行系统模板（仅测试用）
    val dbField = db.javaClass.getDeclaredField("db").also { it.isAccessible = true }
    val database = dbField.get(db) as Database
    database.useConnection { conn ->
        conn.prepareStatement(
            """
            INSERT OR REPLACE INTO prompt_template
                (id, name, description, category, source_type, script_language,
                 script_code, schema_target, created_at, updated_at)
            VALUES (?, 'sys template', '', '', 'system', 'javascript', '', '', 0, 0)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, id)
            ps.executeUpdate()
        }
    }
}
