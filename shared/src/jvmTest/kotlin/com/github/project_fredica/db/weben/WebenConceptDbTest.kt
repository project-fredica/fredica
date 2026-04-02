package com.github.project_fredica.db.weben

import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebenConceptDbTest {

    private lateinit var db: Database
    private lateinit var conceptDb: WebenConceptDb

    @BeforeTest
    fun setup() = runBlocking {
        val tmpFile = File.createTempFile("webenconceptdbtest_", ".db").also { it.deleteOnExit() }
        db = Database.connect("jdbc:sqlite:${tmpFile.absolutePath}", "org.sqlite.JDBC")
        conceptDb = WebenConceptDb(db)
        conceptDb.initialize()
        WebenConceptService.initialize(conceptDb)
    }

    private fun nowSec() = System.currentTimeMillis() / 1000L

    private fun makeConcept(
        id: String = java.util.UUID.randomUUID().toString(),
        canonicalName: String = "gpio",
        materialId: String? = null,
        conceptType: String = "术语",
        briefDefinition: String? = null,
    ) = WebenConcept(
        id              = id,
        materialId      = materialId,
        canonicalName   = canonicalName,
        conceptType     = conceptType,
        briefDefinition = briefDefinition,
        firstSeenAt     = nowSec(),
        lastSeenAt      = nowSec(),
        createdAt       = nowSec(),
        updatedAt       = nowSec(),
    )

    // ─── 核心：素材作用域隔离 ──────────────────────────────────────────────────────

    @Test
    fun `same canonical_name in different materials are independent records`() = runBlocking {
        // 最重要的测试：同名概念在不同素材中必须是独立行，不能因 canonical_name 冲突而丢失
        val a = makeConcept(id = "id-a", canonicalName = "gpio", materialId = "mat-a")
        val b = makeConcept(id = "id-b", canonicalName = "gpio", materialId = "mat-b")
        conceptDb.create(a)
        conceptDb.create(b)

        assertNotNull(conceptDb.getById("id-a"))
        assertNotNull(conceptDb.getById("id-b"))
        assertEquals(2, conceptDb.count())
    }

    @Test
    fun `getByCanonicalName is scoped to materialId`() = runBlocking {
        conceptDb.create(makeConcept(id = "id-a", canonicalName = "gpio", materialId = "mat-a"))
        conceptDb.create(makeConcept(id = "id-b", canonicalName = "gpio", materialId = "mat-b"))

        val foundA = conceptDb.getByCanonicalName("gpio", materialId = "mat-a")
        assertNotNull(foundA)
        assertEquals("id-a", foundA.id)

        val foundB = conceptDb.getByCanonicalName("gpio", materialId = "mat-b")
        assertNotNull(foundB)
        assertEquals("id-b", foundB.id)

        // 不存在的素材返回 null
        assertNull(conceptDb.getByCanonicalName("gpio", materialId = "mat-x"))
    }

    @Test
    fun `create is idempotent within same material on id conflict`() = runBlocking {
        val concept = makeConcept(id = "fixed-id", canonicalName = "gpio", materialId = "mat-a")
        conceptDb.create(concept)
        conceptDb.create(concept.copy(canonicalName = "different"))  // same id → ignored
        assertEquals("gpio", conceptDb.getById("fixed-id")!!.canonicalName)
    }

    @Test
    fun `create is idempotent within same material on (material_id, canonical_name) conflict`() = runBlocking {
        // 同一素材内同名：第二次 create（id 不同）应被忽略
        conceptDb.create(makeConcept(id = "id-1", canonicalName = "gpio", materialId = "mat-a"))
        conceptDb.create(makeConcept(id = "id-2", canonicalName = "gpio", materialId = "mat-a"))
        assertEquals(1, conceptDb.count(materialId = "mat-a"))
    }

    // ─── listAll / count 过滤 ─────────────────────────────────────────────────────

    @Test
    fun `listAll with materialId filter returns only that material concepts`() = runBlocking {
        conceptDb.create(makeConcept(canonicalName = "gpio",  materialId = "mat-a"))
        conceptDb.create(makeConcept(canonicalName = "链表",  materialId = "mat-b"))
        conceptDb.create(makeConcept(canonicalName = "算法",  materialId = "mat-a"))

        val result = conceptDb.listAll(materialId = "mat-a")
        assertEquals(2, result.size)
        assertTrue(result.all { it.materialId == "mat-a" })
        assertTrue(result.none { it.canonicalName == "链表" })
    }

    @Test
    fun `listAll returns all when no filter`() = runBlocking {
        conceptDb.create(makeConcept(canonicalName = "a", materialId = "mat-a"))
        conceptDb.create(makeConcept(canonicalName = "b", materialId = "mat-b"))
        assertEquals(2, conceptDb.listAll().size)
    }

    @Test
    fun `listAll returns results ordered by canonical_name`() = runBlocking {
        conceptDb.create(makeConcept(canonicalName = "c", materialId = "mat-a"))
        conceptDb.create(makeConcept(canonicalName = "a", materialId = "mat-a"))
        conceptDb.create(makeConcept(canonicalName = "b", materialId = "mat-a"))
        val result = conceptDb.listAll(materialId = "mat-a")
        assertEquals(listOf("a", "b", "c"), result.map { it.canonicalName })
    }

    @Test
    fun `listAll pagination`() = runBlocking {
        repeat(5) { i -> conceptDb.create(makeConcept(canonicalName = "concept$i", materialId = "mat-a")) }
        val page1 = conceptDb.listAll(materialId = "mat-a", limit = 2, offset = 0)
        val page2 = conceptDb.listAll(materialId = "mat-a", limit = 2, offset = 2)
        assertEquals(2, page1.size)
        assertEquals(2, page2.size)
        assertNotEquals(page1[0].id, page2[0].id)
    }

    @Test
    fun `listAll with conceptType filter matches token in comma-separated types`() = runBlocking {
        conceptDb.create(makeConcept(canonicalName = "二叉树", materialId = "mat-a", conceptType = "术语,数学领域"))
        conceptDb.create(makeConcept(canonicalName = "导数",   materialId = "mat-a", conceptType = "数学领域"))
        conceptDb.create(makeConcept(canonicalName = "链表",   materialId = "mat-a", conceptType = "数据结构"))
        val result = conceptDb.listAll(conceptType = "数学领域")
        assertEquals(2, result.size)
        assertTrue(result.any { it.canonicalName == "二叉树" })
        assertTrue(result.any { it.canonicalName == "导数" })
    }

    @Test
    fun `listAll with conceptType filter does not partial-match type name`() = runBlocking {
        // "数学" 不应匹配 "数学领域"
        conceptDb.create(makeConcept(canonicalName = "导数",   materialId = "mat-a", conceptType = "数学领域"))
        conceptDb.create(makeConcept(canonicalName = "概率论", materialId = "mat-a", conceptType = "数学"))
        val result = conceptDb.listAll(conceptType = "数学")
        assertEquals(1, result.size)
        assertEquals("概率论", result[0].canonicalName)
    }

    @Test
    fun `count with materialId filter counts only that material`() = runBlocking {
        conceptDb.create(makeConcept(canonicalName = "gpio", materialId = "mat-a"))
        conceptDb.create(makeConcept(canonicalName = "链表", materialId = "mat-b"))
        conceptDb.create(makeConcept(canonicalName = "算法", materialId = "mat-a"))
        assertEquals(2, conceptDb.count(materialId = "mat-a"))
        assertEquals(1, conceptDb.count(materialId = "mat-b"))
        assertEquals(3, conceptDb.count())
    }

    @Test
    fun `count with conceptType filter handles comma-separated types`() = runBlocking {
        conceptDb.create(makeConcept(canonicalName = "二叉树", materialId = "mat-a", conceptType = "术语,数学领域"))
        conceptDb.create(makeConcept(canonicalName = "导数",   materialId = "mat-a", conceptType = "数学领域"))
        conceptDb.create(makeConcept(canonicalName = "链表",   materialId = "mat-a", conceptType = "数据结构"))
        assertEquals(2, conceptDb.count(conceptType = "数学领域"))
        assertEquals(1, conceptDb.count(conceptType = "数据结构"))
        assertEquals(0, conceptDb.count(conceptType = "算法"))
    }

    // ─── getById / getByCanonicalName ─────────────────────────────────────────────

    @Test
    fun `getById returns correct concept with materialId`() = runBlocking {
        val concept = makeConcept(canonicalName = "pwm", materialId = "mat-a", conceptType = "理论")
        conceptDb.create(concept)
        val found = conceptDb.getById(concept.id)
        assertNotNull(found)
        assertEquals("pwm", found.canonicalName)
        assertEquals("mat-a", found.materialId)
        assertEquals("理论", found.conceptType)
    }

    @Test
    fun `getByCanonicalName returns null for unknown name`() = runBlocking {
        assertNull(conceptDb.getByCanonicalName("nonexistent", materialId = "mat-a"))
    }

    // ─── update ───────────────────────────────────────────────────────────────────

    @Test
    fun `update modifies definition and metadata`() = runBlocking {
        val concept = makeConcept(briefDefinition = "旧定义", materialId = "mat-a")
        conceptDb.create(concept)
        val nowSec = nowSec()
        conceptDb.update(concept.copy(briefDefinition = "新定义", metadataJson = """{"source":"manual"}""", updatedAt = nowSec))
        val found = conceptDb.getById(concept.id)!!
        assertEquals("新定义", found.briefDefinition)
        assertEquals("""{"source":"manual"}""", found.metadataJson)
    }

    // ─── 别名 ─────────────────────────────────────────────────────────────────────

    @Test
    fun `addAlias and listAliases`() = runBlocking {
        val concept = makeConcept(materialId = "mat-a")
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
        val concept = makeConcept(materialId = "mat-a")
        conceptDb.create(concept)
        conceptDb.addAlias(WebenConceptAlias(conceptId = concept.id, alias = "GPIO"))
        conceptDb.addAlias(WebenConceptAlias(conceptId = concept.id, alias = "GPIO"))
        assertEquals(1, conceptDb.listAliases(concept.id).size)
    }

    @Test
    fun `deleteAlias removes correct entry`() = runBlocking {
        val concept = makeConcept(materialId = "mat-a")
        conceptDb.create(concept)
        conceptDb.addAlias(WebenConceptAlias(conceptId = concept.id, alias = "GPIO"))
        conceptDb.addAlias(WebenConceptAlias(conceptId = concept.id, alias = "IO口"))
        val toDelete = conceptDb.listAliases(concept.id).first { it.alias == "GPIO" }
        conceptDb.deleteAlias(toDelete.id)
        val remaining = conceptDb.listAliases(concept.id)
        assertEquals(1, remaining.size)
        assertEquals("IO口", remaining[0].alias)
    }

    // ─── 来源关联 ─────────────────────────────────────────────────────────────────

    @Test
    fun `addSource and listSources`() = runBlocking {
        val concept = makeConcept(materialId = "mat-a")
        conceptDb.create(concept)
        val sourceId = java.util.UUID.randomUUID().toString()
        conceptDb.addSource(WebenConceptSource(conceptId = concept.id, sourceId = sourceId, timestampSec = 120.5, excerpt = "GPIO是..."))
        conceptDb.addSource(WebenConceptSource(conceptId = concept.id, sourceId = sourceId, timestampSec = null))
        val sources = conceptDb.listSources(concept.id)
        assertEquals(2, sources.size)
        assertTrue(sources.any { it.timestampSec == 120.5 })
        assertTrue(sources.any { it.timestampSec == null })
    }
}
