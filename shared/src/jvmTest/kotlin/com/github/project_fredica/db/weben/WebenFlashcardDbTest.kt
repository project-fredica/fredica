package com.github.project_fredica.db.weben

// =============================================================================
// WebenFlashcardDbTest —— 闪卡 + SM-2 + mastery 缓存重算单元测试
// =============================================================================
//
// 测试范围：
//   1. create / getById / listByConcept — 基础 CRUD
//   2. SM2Algorithm.update              — 算法正确性
//      a. 遗忘（rating < 3）         → interval 重置为 1.0
//      b. 首次通过（currentInterval <= 1.0, rating >= 3）→ interval = 6.0
//      c. 后续通过（rating >= 3）    → interval = currentInterval * newEF
//      d. ease_factor 下限           → newEF >= 1.3
//   3. review()                         — 全路径事务测试
//      a. 更新 ease_factor / interval_days / next_review_at
//      b. review_count +1
//      c. 追加 weben_mastery_record 历史
//      d. 更新 weben_concept.mastery 缓存
//   4. listDueForReview                 — 仅返回 next_review_at <= now 的卡
//   5. listMasteryHistory               — 按概念聚合历史
//
// =============================================================================

import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.*

class WebenFlashcardDbTest {

    private lateinit var db: Database
    private lateinit var conceptDb: WebenConceptDb
    private lateinit var flashcardDb: WebenFlashcardDb

    @BeforeTest
    fun setup() = runBlocking {
        val tmpFile = File.createTempFile("webenflashcarddbtest_", ".db").also { it.deleteOnExit() }
        db          = Database.connect("jdbc:sqlite:${tmpFile.absolutePath}", "org.sqlite.JDBC")
        conceptDb   = WebenConceptDb(db)
        flashcardDb = WebenFlashcardDb(db)
        conceptDb.initialize()
        flashcardDb.initialize()
        WebenConceptService.initialize(conceptDb)
        WebenFlashcardService.initialize(flashcardDb)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun nowSec() = System.currentTimeMillis() / 1000L

    private suspend fun createConcept(name: String = "gpio"): WebenConcept {
        val concept = WebenConcept(
            id            = java.util.UUID.randomUUID().toString(),
            canonicalName = name,
            conceptType   = "术语",
            firstSeenAt   = nowSec(),
            lastSeenAt    = nowSec(),
            createdAt     = nowSec(),
            updatedAt     = nowSec(),
        )
        conceptDb.create(concept)
        return concept
    }

    private fun makeCard(conceptId: String, nowSec: Long = nowSec()) = WebenFlashcard(
        id           = java.util.UUID.randomUUID().toString(),
        conceptId    = conceptId,
        question     = "GPIO的两种输出模式？",
        answer       = "推挽输出 / 开漏输出",
        nextReviewAt = nowSec - 1L, // already due
        createdAt    = nowSec,
    )

    // ── Test 1: create / getById / listByConcept ──────────────────────────────

    @Test
    fun `create and getById round-trip`() = runBlocking {
        val concept = createConcept()
        val card    = makeCard(concept.id)
        flashcardDb.create(card)
        val found = flashcardDb.getById(card.id)
        assertNotNull(found)
        assertEquals("GPIO的两种输出模式？", found.question)
        assertEquals(2.5, found.easeFactor, absoluteTolerance = 0.001)
        assertEquals(0, found.reviewCount)
    }

    @Test
    fun `listByConcept returns all cards for concept`() = runBlocking {
        val concept = createConcept()
        val other   = createConcept("spi")
        flashcardDb.create(makeCard(concept.id).copy(question = "Q1"))
        flashcardDb.create(makeCard(concept.id).copy(question = "Q2"))
        flashcardDb.create(makeCard(other.id).copy(question = "Q_other"))

        val cards = flashcardDb.listByConcept(concept.id)
        assertEquals(2, cards.size)
        assertTrue(cards.all { it.conceptId == concept.id })
    }

    // ── Test 2: SM2Algorithm.update ───────────────────────────────────────────

    @Test
    fun `SM2 rating less than 3 resets interval`() {
        val (newEF, newInterval) = SM2Algorithm.update(2.5, 6.0, rating = 2)
        assertEquals(1.0, newInterval, absoluteTolerance = 0.001)
        assertTrue(newEF >= 1.3) // floor applies
    }

    @Test
    fun `SM2 first pass with interval 1 sets interval to 6`() {
        val (_, newInterval) = SM2Algorithm.update(2.5, 1.0, rating = 4)
        assertEquals(6.0, newInterval, absoluteTolerance = 0.001)
    }

    @Test
    fun `SM2 subsequent pass multiplies interval by newEF`() {
        val currentEF       = 2.5
        val currentInterval = 6.0
        val (newEF, newInterval) = SM2Algorithm.update(currentEF, currentInterval, rating = 5)
        val expectedNewEF = (currentEF + 0.1 - 0 * (0.08 + 0 * 0.02)).coerceAtLeast(1.3)
        assertEquals(expectedNewEF, newEF, absoluteTolerance = 0.001)
        assertEquals(currentInterval * newEF, newInterval, absoluteTolerance = 0.01)
    }

    @Test
    fun `SM2 ease_factor has floor at 1_3`() {
        // Rating 0 repeatedly should bottom out at 1.3
        var ef = 1.4
        repeat(10) { ef = SM2Algorithm.update(ef, 1.0, rating = 0).first }
        assertTrue(ef >= 1.3, "ease_factor should never go below 1.3, got $ef")
    }

    // ── Test 3: review() — full path ──────────────────────────────────────────

    @Test
    fun `review quiz updates flashcard and increments review_count`() = runBlocking {
        val concept = createConcept()
        val card    = makeCard(concept.id)
        flashcardDb.create(card)

        val now = nowSec()
        flashcardDb.review(card.id, rating = 4, reviewType = "quiz", nowSeconds = now)

        val updated = flashcardDb.getById(card.id)!!
        assertEquals(1, updated.reviewCount)
        assertTrue(updated.easeFactor != 2.5 || updated.intervalDays != 1.0) // changed
        assertTrue(updated.nextReviewAt > now) // future scheduled
    }

    @Test
    fun `review appends mastery_record history`() = runBlocking {
        val concept = createConcept()
        val card    = makeCard(concept.id)
        flashcardDb.create(card)

        flashcardDb.review(card.id, rating = 3, reviewType = "quiz", nowSeconds = nowSec())
        flashcardDb.review(card.id, rating = 4, reviewType = "quiz", nowSeconds = nowSec())

        val history = flashcardDb.listMasteryHistory(concept.id)
        assertEquals(2, history.size)
        assertTrue(history.all { it.conceptId == concept.id })
        assertTrue(history.all { it.reviewType == "quiz" })
    }

    @Test
    fun `review updates concept mastery cache`() = runBlocking {
        val concept = createConcept()
        val card    = makeCard(concept.id)
        flashcardDb.create(card)

        // Before review: mastery = 0.0
        assertEquals(0.0, conceptDb.getById(concept.id)!!.mastery, absoluteTolerance = 0.001)

        flashcardDb.review(card.id, rating = 5, reviewType = "quiz", nowSeconds = nowSec())

        // After review: mastery should be > 0 (ease_factor increased from 2.5)
        val masteryAfter = conceptDb.getById(concept.id)!!.mastery
        assertTrue(masteryAfter > 0.0, "mastery should be > 0 after a rating=5 review, got $masteryAfter")
    }

    @Test
    fun `mastery formula avg ef minus 1_3 div 3_7 normalizes to 0 to 1`() = runBlocking {
        val concept = createConcept()
        // Create two flashcards with known ease_factors; force mastery recompute via review
        val card1 = makeCard(concept.id)
        val card2 = makeCard(concept.id).copy(question = "Q2")
        flashcardDb.create(card1)
        flashcardDb.create(card2)

        // Perfect score on both → ease_factor will increase above 2.5
        val now = nowSec()
        flashcardDb.review(card1.id, rating = 5, reviewType = "quiz", nowSeconds = now)
        flashcardDb.review(card2.id, rating = 5, reviewType = "quiz", nowSeconds = now)

        val mastery = conceptDb.getById(concept.id)!!.mastery
        assertTrue(mastery in 0.0..1.0, "mastery out of bounds: $mastery")
    }

    @Test
    fun `review view type does not change SM-2 state`() = runBlocking {
        val concept = createConcept()
        val card    = makeCard(concept.id)
        flashcardDb.create(card)

        val efBefore       = flashcardDb.getById(card.id)!!.easeFactor
        val intervalBefore = flashcardDb.getById(card.id)!!.intervalDays

        flashcardDb.review(card.id, rating = null, reviewType = "view", nowSeconds = nowSec())

        val updated = flashcardDb.getById(card.id)!!
        assertEquals(efBefore, updated.easeFactor, absoluteTolerance = 0.001)
        assertEquals(intervalBefore, updated.intervalDays, absoluteTolerance = 0.001)
        assertEquals(1, updated.reviewCount) // view still increments count
    }

    // ── Test 4: listDueForReview ──────────────────────────────────────────────

    @Test
    fun `listDueForReview only returns overdue cards`() = runBlocking {
        val concept = createConcept()
        val now     = nowSec()
        val dueCard = makeCard(concept.id).copy(nextReviewAt = now - 100L)
        val futureCard = makeCard(concept.id).copy(nextReviewAt = now + 86400L)
        flashcardDb.create(dueCard)
        flashcardDb.create(futureCard)

        val queue = flashcardDb.listDueForReview(now)
        assertEquals(1, queue.size)
        assertEquals(dueCard.id, queue[0].id)
    }
}
