package com.github.project_fredica.db.weben

// =============================================================================
// WebenConceptDbTest —— 概念数据层单元测试
// =============================================================================
//
// 测试范围：
//   1. create / getById         — 创建与查询，字段正确落库
//   2. getByCanonicalName        — 按规范名查询（去重基础）
//   3. listAll / 分页过滤        — 瀑布流查询（按类型过滤 + 掌握度排序）
//   4. update                   — 更新定义/元数据，mastery 不被覆盖
//   5. alias CRUD               — 别名增删查（UNIQUE 约束去重）
//   6. concept_source CRUD      — 来源关联增查
//   7. mastery 缓存不变量       — updateMastery 正确写入，不影响其他字段
//
// =============================================================================

import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.*

class WebenConceptDbTest {

    private lateinit var db: Database
    private lateinit var conceptDb: WebenConceptDb

    @BeforeTest
    fun setup() = runBlocking {
        val tmpFile = File.createTempFile("webenconceptdbtest_", ".db").also { it.deleteOnExit() }
        db        = Database.connect("jdbc:sqlite:${tmpFile.absolutePath}", "org.sqlite.JDBC")
        conceptDb = WebenConceptDb(db)
        conceptDb.initialize()
        WebenConceptService.initialize(conceptDb)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun nowSec() = System.currentTimeMillis() / 1000L

    private fun makeConcept(
        id: String = java.util.UUID.randomUUID().toString(),
        canonicalName: String = "gpio",
        conceptType: String = "术语",
        mastery: Double = 0.0,
    ) = WebenConcept(
        id            = id,
        canonicalName = canonicalName,
        conceptType   = conceptType,
        mastery       = mastery,
        firstSeenAt   = nowSec(),
        lastSeenAt    = nowSec(),
        createdAt     = nowSec(),
        updatedAt     = nowSec(),
    )

    // ── Test 1: create / getById ──────────────────────────────────────────────

    @Test
    fun `create and getById round-trip`() = runBlocking {
        val concept = makeConcept(canonicalName = "pwm输出", conceptType = "理论")
        conceptDb.create(concept)
        val found = conceptDb.getById(concept.id)
        assertNotNull(found)
        assertEquals("pwm输出", found.canonicalName)
        assertEquals("理论", found.conceptType)
        assertEquals(0.0, found.mastery)
    }

    @Test
    fun `create is idempotent on id conflict`() = runBlocking {
        val concept = makeConcept(id = "fixed-id")
        conceptDb.create(concept)
        conceptDb.create(concept.copy(canonicalName = "different")) // ON CONFLICT DO NOTHING
        val found = conceptDb.getById("fixed-id")
        assertNotNull(found)
        assertEquals("gpio", found.canonicalName) // original retained
    }

    // ── Test 2: getByCanonicalName ────────────────────────────────────────────

    @Test
    fun `getByCanonicalName returns correct concept`() = runBlocking {
        val concept = makeConcept(canonicalName = "uart协议")
        conceptDb.create(concept)
        val found = conceptDb.getByCanonicalName("uart协议")
        assertNotNull(found)
        assertEquals(concept.id, found.id)
    }

    @Test
    fun `getByCanonicalName returns null for unknown name`() = runBlocking {
        assertNull(conceptDb.getByCanonicalName("nonexistent"))
    }

    // ── Test 3: listAll / 分页过滤 ────────────────────────────────────────────

    @Test
    fun `listAll returns all concepts ordered by mastery asc`() = runBlocking {
        conceptDb.create(makeConcept(canonicalName = "a", mastery = 0.8))
        conceptDb.create(makeConcept(canonicalName = "b", mastery = 0.2))
        conceptDb.create(makeConcept(canonicalName = "c", mastery = 0.5))
        val list = conceptDb.listAll()
        assertEquals(3, list.size)
        assertEquals("b", list[0].canonicalName) // 0.2 lowest mastery first
        assertEquals("c", list[1].canonicalName)
        assertEquals("a", list[2].canonicalName)
    }

    @Test
    fun `listAll with conceptType filter`() = runBlocking {
        conceptDb.create(makeConcept(canonicalName = "x", conceptType = "理论"))
        conceptDb.create(makeConcept(canonicalName = "y", conceptType = "术语"))
        conceptDb.create(makeConcept(canonicalName = "z", conceptType = "理论"))
        val theories = conceptDb.listAll(conceptType = "理论")
        assertEquals(2, theories.size)
        assertTrue(theories.all { it.conceptType == "理论" })
    }

    @Test
    fun `listAll pagination`() = runBlocking {
        repeat(5) { i -> conceptDb.create(makeConcept(canonicalName = "concept$i", mastery = i * 0.1)) }
        val page1 = conceptDb.listAll(limit = 2, offset = 0)
        val page2 = conceptDb.listAll(limit = 2, offset = 2)
        assertEquals(2, page1.size)
        assertEquals(2, page2.size)
        assertNotEquals(page1[0].id, page2[0].id)
    }

    // ── Test 4: update ────────────────────────────────────────────────────────

    @Test
    fun `update modifies definition but not mastery`() = runBlocking {
        val concept = makeConcept(mastery = 0.0)
        conceptDb.create(concept)
        conceptDb.updateMastery(concept.id, 0.7) // simulate flashcard review

        val nowSec = System.currentTimeMillis() / 1000L
        conceptDb.update(concept.copy(briefDefinition = "GPIO是通用输入输出接口", updatedAt = nowSec))

        val found = conceptDb.getById(concept.id)!!
        assertEquals("GPIO是通用输入输出接口", found.briefDefinition)
        // mastery must NOT be overwritten by update()
        assertEquals(0.7, found.mastery, absoluteTolerance = 0.001)
    }

    // ── Test 5: mastery 缓存不变量 ─────────────────────────────────────────────

    @Test
    fun `updateMastery correctly persists value`() = runBlocking {
        val concept = makeConcept()
        conceptDb.create(concept)
        conceptDb.updateMastery(concept.id, 0.65)
        val found = conceptDb.getById(concept.id)!!
        assertEquals(0.65, found.mastery, absoluteTolerance = 0.001)
    }

    // ── Test 6: alias CRUD ────────────────────────────────────────────────────

    @Test
    fun `addAlias and listAliases`() = runBlocking {
        val concept = makeConcept()
        conceptDb.create(concept)
        conceptDb.addAlias(WebenConceptAlias(conceptId = concept.id, alias = "GPIO", aliasSource = "LLM提取"))
        conceptDb.addAlias(WebenConceptAlias(conceptId = concept.id, alias = "General Purpose IO"))

        val aliases = conceptDb.listAliases(concept.id)
        assertEquals(2, aliases.size)
        assertTrue(aliases.any { it.alias == "GPIO" })
        assertTrue(aliases.any { it.alias == "General Purpose IO" })
    }

    @Test
    fun `addAlias is idempotent on duplicate`() = runBlocking {
        val concept = makeConcept()
        conceptDb.create(concept)
        conceptDb.addAlias(WebenConceptAlias(conceptId = concept.id, alias = "GPIO"))
        conceptDb.addAlias(WebenConceptAlias(conceptId = concept.id, alias = "GPIO")) // duplicate
        assertEquals(1, conceptDb.listAliases(concept.id).size)
    }

    @Test
    fun `deleteAlias removes correct entry`() = runBlocking {
        val concept = makeConcept()
        conceptDb.create(concept)
        conceptDb.addAlias(WebenConceptAlias(conceptId = concept.id, alias = "GPIO"))
        conceptDb.addAlias(WebenConceptAlias(conceptId = concept.id, alias = "IO口"))

        val aliases = conceptDb.listAliases(concept.id)
        val toDelete = aliases.first { it.alias == "GPIO" }
        conceptDb.deleteAlias(toDelete.id)

        val remaining = conceptDb.listAliases(concept.id)
        assertEquals(1, remaining.size)
        assertEquals("IO口", remaining[0].alias)
    }

    // ── Test 7: concept_source CRUD ───────────────────────────────────────────

    @Test
    fun `addSource and listSources`() = runBlocking {
        val concept = makeConcept()
        conceptDb.create(concept)
        val sourceId = java.util.UUID.randomUUID().toString()

        conceptDb.addSource(WebenConceptSource(
            conceptId    = concept.id,
            sourceId     = sourceId,
            timestampSec = 120.5,
            excerpt      = "GPIO是通用输入输出...",
        ))
        conceptDb.addSource(WebenConceptSource(
            conceptId = concept.id,
            sourceId  = sourceId,
            timestampSec = null,
        ))

        val sources = conceptDb.listSources(concept.id)
        assertEquals(2, sources.size)
        assertTrue(sources.any { it.timestampSec == 120.5 })
        assertTrue(sources.any { it.timestampSec == null })
    }
}
