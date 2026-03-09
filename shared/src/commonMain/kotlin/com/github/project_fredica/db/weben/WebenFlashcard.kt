package com.github.project_fredica.db.weben

// =============================================================================
// WebenFlashcard —— 闪卡 / SM-2 复习单元
// =============================================================================
//
// 管理两张表：
//   weben_flashcard      — SM-2 状态主表（ease_factor / interval_days / next_review_at）
//   weben_mastery_record — 每次复习历史快照（追加写入，绘制掌握度曲线用）
//
// 关键副作用：review() 在同一事务内：
//   1. 更新 weben_flashcard（SM-2 状态 + review_count +1）
//   2. 追加 weben_mastery_record（历史快照）
//   3. 重算并更新 weben_concept.mastery 缓存
//      公式：avg((ease_factor - 1.3) / (5.0 - 1.3)) 对该概念所有闪卡取平均
// =============================================================================

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.ktorm.database.Database
import java.sql.ResultSet
import java.sql.Types

// =============================================================================
// SM2Algorithm
// =============================================================================

object SM2Algorithm {
    /**
     * 更新单张闪卡的 SM-2 状态。
     *
     * @param currentEF       当前 ease factor（初始 2.5，最低 1.3）
     * @param currentInterval 当前复习间隔（天）
     * @param rating          本次评分：0-5（< 3 视为遗忘，重置间隔为 1 天）
     * @return Pair(新 ease_factor, 新 interval_days)
     */
    fun update(currentEF: Double, currentInterval: Double, rating: Int): Pair<Double, Double> {
        val newEF = (currentEF + 0.1 - (5 - rating) * (0.08 + (5 - rating) * 0.02))
            .coerceAtLeast(1.3)
        val newInterval = when {
            rating < 3           -> 1.0          // 遗忘，重置
            currentInterval <= 1.0 -> 6.0        // 首次复习通过
            else                 -> currentInterval * newEF
        }
        return Pair(newEF, newInterval)
    }
}

// =============================================================================
// Data classes
// =============================================================================

@Serializable
data class WebenFlashcard(
    /** UUID。 */
    val id: String,
    /** 所属概念（weben_concept.id）。 */
    @SerialName("concept_id") val conceptId: String,
    /** 闪卡来源（weben_source.id）；AI 生成时记录来源视频，用户手动创建时可为 null。 */
    @SerialName("source_id") val sourceId: String? = null,
    /** 题面（正面），如"GPIO 的两种输出模式是什么？" */
    val question: String,
    /** 答案（背面）。 */
    val answer: String,
    /** 卡片类型：'qa'（问答翻转）| 'cloze'（填空，answer 中 {{c1::...}} 标记挖空处）。 */
    @SerialName("card_type") val cardType: String = "qa",
    /** 生成方式：true=AI 自动生成，false=用户手动创建。 */
    @SerialName("is_system") val isSystem: Boolean = true,
    /** SM-2 难易系数（范围 1.3-5.0）；值越低代表越难记。 */
    @SerialName("ease_factor") val easeFactor: Double = 2.5,
    /** SM-2 当前复习间隔（天）。 */
    @SerialName("interval_days") val intervalDays: Double = 1.0,
    /** 【缓存】累计复习次数，每次写入 mastery_record 时 +1。 */
    @SerialName("review_count") val reviewCount: Int = 0,
    /** 下次复习的预定时间（Unix 秒）。 */
    @SerialName("next_review_at") val nextReviewAt: Long,
    /** 记录创建时间，Unix 秒。 */
    @SerialName("created_at") val createdAt: Long,
)

@Serializable
data class WebenMasteryRecord(
    /** 自增主键。 */
    val id: Long = 0,
    /** 被复习的闪卡（weben_flashcard.id）。 */
    @SerialName("flashcard_id") val flashcardId: String,
    /** 【冗余】所属概念，从 flashcard.concept_id 复制，方便按概念聚合。 */
    @SerialName("concept_id") val conceptId: String,
    /** 复习触发方式：'view'（浏览概念卡片）| 'quiz'（主动测验）| 'manual'（用户手动调分）。 */
    @SerialName("review_type") val reviewType: String,
    /** SM-2 质量评分（0-5）；quiz/manual 类型有值，view 类型为 null。 */
    val rating: Int? = null,
    /** 本次评分后的新 ease_factor 快照。 */
    @SerialName("ease_factor_after") val easeFactorAfter: Double,
    /** 本次评分后的新 interval_days 快照。 */
    @SerialName("interval_after") val intervalAfter: Double,
    /** 本次评分后该概念的 mastery 值快照，用于绘制掌握度曲线。 */
    @SerialName("mastery_level_after") val masteryLevelAfter: Double,
    /** 复习发生的时间，Unix 秒。 */
    @SerialName("reviewed_at") val reviewedAt: Long,
)

// =============================================================================
// WebenFlashcardRepo
// =============================================================================

interface WebenFlashcardRepo {
    suspend fun create(card: WebenFlashcard)
    suspend fun getById(id: String): WebenFlashcard?
    suspend fun listByConcept(conceptId: String): List<WebenFlashcard>
    /** 复习队列：返回 next_review_at <= nowSeconds 的闪卡，按到期时间升序。 */
    suspend fun listDueForReview(nowSeconds: Long, limit: Int = 50): List<WebenFlashcard>
    /**
     * 提交复习评分。在单一事务内：
     *   1. SM-2 更新 weben_flashcard（ease_factor / interval_days / review_count / next_review_at）
     *   2. 追加 weben_mastery_record 历史快照
     *   3. 重算并更新 weben_concept.mastery 缓存
     *
     * @param flashcardId 被评分的闪卡 id
     * @param rating      SM-2 评分 0-5；view 类型传 null（此时 SM-2 不更新，仅记录浏览历史）
     * @param reviewType  'view' | 'quiz' | 'manual'
     * @param nowSeconds  评分时间，Unix 秒
     */
    suspend fun review(flashcardId: String, rating: Int?, reviewType: String, nowSeconds: Long)
    /** 按概念查复习历史（掌握度曲线），按时间升序。 */
    suspend fun listMasteryHistory(conceptId: String): List<WebenMasteryRecord>
}

// =============================================================================
// WebenFlashcardService
// =============================================================================

object WebenFlashcardService {
    private var _repo: WebenFlashcardRepo? = null
    val repo: WebenFlashcardRepo
        get() = _repo ?: error("WebenFlashcardService 未初始化，请先调用 initialize()")
    fun initialize(repo: WebenFlashcardRepo) { _repo = repo }
}

// =============================================================================
// WebenFlashcardDb —— weben_flashcard / weben_mastery_record 表的 JDBC 实现
// =============================================================================

class WebenFlashcardDb(private val db: Database) : WebenFlashcardRepo {

    suspend fun initialize() = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                // weben_flashcard
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS weben_flashcard (
                        id             TEXT    PRIMARY KEY,
                        concept_id     TEXT    NOT NULL,
                        source_id      TEXT,
                        question       TEXT    NOT NULL,
                        answer         TEXT    NOT NULL,
                        card_type      TEXT    NOT NULL DEFAULT 'qa',
                        is_system      INTEGER NOT NULL DEFAULT 1,
                        ease_factor    REAL    NOT NULL DEFAULT 2.5,
                        interval_days  REAL    NOT NULL DEFAULT 1.0,
                        review_count   INTEGER NOT NULL DEFAULT 0,
                        next_review_at INTEGER NOT NULL,
                        created_at     INTEGER NOT NULL
                    )
                """.trimIndent())
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_wfl_concept ON weben_flashcard(concept_id)"
                )
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_wfl_next_rev ON weben_flashcard(next_review_at)"
                )
                // weben_mastery_record
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS weben_mastery_record (
                        id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                        flashcard_id        TEXT    NOT NULL,
                        concept_id          TEXT    NOT NULL,
                        review_type         TEXT    NOT NULL,
                        rating              INTEGER,
                        ease_factor_after   REAL    NOT NULL,
                        interval_after      REAL    NOT NULL,
                        mastery_level_after REAL    NOT NULL,
                        reviewed_at         INTEGER NOT NULL
                    )
                """.trimIndent())
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_wmr_flashcard ON weben_mastery_record(flashcard_id)"
                )
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_wmr_concept_reviewed ON weben_mastery_record(concept_id, reviewed_at DESC)"
                )
            }
        }
    }

    override suspend fun create(card: WebenFlashcard) = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("""
                INSERT INTO weben_flashcard
                    (id, concept_id, source_id, question, answer, card_type, is_system,
                     ease_factor, interval_days, review_count, next_review_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO NOTHING
            """.trimIndent()).use { ps ->
                ps.setString(1, card.id)
                ps.setString(2, card.conceptId)
                ps.setStringOrNull(3, card.sourceId)
                ps.setString(4, card.question)
                ps.setString(5, card.answer)
                ps.setString(6, card.cardType)
                ps.setInt(7, if (card.isSystem) 1 else 0)
                ps.setDouble(8, card.easeFactor)
                ps.setDouble(9, card.intervalDays)
                ps.setInt(10, card.reviewCount)
                ps.setLong(11, card.nextReviewAt)
                ps.setLong(12, card.createdAt)
                ps.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun getById(id: String): WebenFlashcard? = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("SELECT * FROM weben_flashcard WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toFlashcard() else null }
            }
        }
    }

    override suspend fun listByConcept(conceptId: String): List<WebenFlashcard> =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM weben_flashcard WHERE concept_id = ? ORDER BY created_at ASC"
                ).use { ps ->
                    ps.setString(1, conceptId)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toFlashcard()) } }
                }
            }
        }

    override suspend fun listDueForReview(nowSeconds: Long, limit: Int): List<WebenFlashcard> =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM weben_flashcard WHERE next_review_at <= ? ORDER BY next_review_at ASC LIMIT ?"
                ).use { ps ->
                    ps.setLong(1, nowSeconds)
                    ps.setInt(2, limit)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toFlashcard()) } }
                }
            }
        }

    override suspend fun review(
        flashcardId: String,
        rating: Int?,
        reviewType: String,
        nowSeconds: Long,
    ) = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.autoCommit = false
            try {
                // 1. 读取当前闪卡
                val card = conn.prepareStatement(
                    "SELECT * FROM weben_flashcard WHERE id = ?"
                ).use { ps ->
                    ps.setString(1, flashcardId)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.toFlashcard() else null }
                } ?: error("Flashcard not found: $flashcardId")

                // 2. 计算新 SM-2 状态（view 类型不更新 SM-2，只记录浏览）
                val (newEF, newInterval) = if (rating != null)
                    SM2Algorithm.update(card.easeFactor, card.intervalDays, rating)
                else
                    Pair(card.easeFactor, card.intervalDays)

                val newNextReviewAt = nowSeconds + (newInterval * 86400L).toLong()

                // 3. 更新 weben_flashcard（无论 view/quiz 都 +1 review_count）
                conn.prepareStatement("""
                    UPDATE weben_flashcard
                    SET ease_factor    = ?,
                        interval_days  = ?,
                        review_count   = review_count + 1,
                        next_review_at = ?
                    WHERE id = ?
                """.trimIndent()).use { ps ->
                    ps.setDouble(1, newEF)
                    ps.setDouble(2, newInterval)
                    ps.setLong(3, newNextReviewAt)
                    ps.setString(4, flashcardId)
                    ps.executeUpdate()
                }

                // 4. 重算该概念的 mastery 缓存：avg((ease_factor - 1.3) / 3.7)，归一到 [0, 1]
                val newMastery = conn.prepareStatement(
                    "SELECT AVG((ease_factor - 1.3) / 3.7) FROM weben_flashcard WHERE concept_id = ?"
                ).use { ps ->
                    ps.setString(1, card.conceptId)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.getDouble(1).coerceIn(0.0, 1.0) else 0.0
                    }
                }

                // 5. 更新 weben_concept.mastery 缓存
                conn.prepareStatement(
                    "UPDATE weben_concept SET mastery = ? WHERE id = ?"
                ).use { ps ->
                    ps.setDouble(1, newMastery)
                    ps.setString(2, card.conceptId)
                    ps.executeUpdate()
                }

                // 6. 追加 weben_mastery_record 历史快照
                conn.prepareStatement("""
                    INSERT INTO weben_mastery_record
                        (flashcard_id, concept_id, review_type, rating,
                         ease_factor_after, interval_after, mastery_level_after, reviewed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { ps ->
                    ps.setString(1, flashcardId)
                    ps.setString(2, card.conceptId)
                    ps.setString(3, reviewType)
                    if (rating != null) ps.setInt(4, rating) else ps.setNull(4, Types.INTEGER)
                    ps.setDouble(5, newEF)
                    ps.setDouble(6, newInterval)
                    ps.setDouble(7, newMastery)
                    ps.setLong(8, nowSeconds)
                    ps.executeUpdate()
                }

                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override suspend fun listMasteryHistory(conceptId: String): List<WebenMasteryRecord> =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "SELECT * FROM weben_mastery_record WHERE concept_id = ? ORDER BY reviewed_at ASC"
                ).use { ps ->
                    ps.setString(1, conceptId)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toRecord()) } }
                }
            }
        }

    private fun ResultSet.toFlashcard() = WebenFlashcard(
        id           = getString("id"),
        conceptId    = getString("concept_id"),
        sourceId     = getString("source_id"),
        question     = getString("question"),
        answer       = getString("answer"),
        cardType     = getString("card_type"),
        isSystem     = getInt("is_system") != 0,
        easeFactor   = getDouble("ease_factor"),
        intervalDays = getDouble("interval_days"),
        reviewCount  = getInt("review_count"),
        nextReviewAt = getLong("next_review_at"),
        createdAt    = getLong("created_at"),
    )

    private fun ResultSet.toRecord() = WebenMasteryRecord(
        id                = getLong("id"),
        flashcardId       = getString("flashcard_id"),
        conceptId         = getString("concept_id"),
        reviewType        = getString("review_type"),
        rating            = getInt("rating").takeIf { !wasNull() },
        easeFactorAfter   = getDouble("ease_factor_after"),
        intervalAfter     = getDouble("interval_after"),
        masteryLevelAfter = getDouble("mastery_level_after"),
        reviewedAt        = getLong("reviewed_at"),
    )
}

private fun java.sql.PreparedStatement.setStringOrNull(idx: Int, v: String?) {
    if (v != null) setString(idx, v) else setNull(idx, Types.VARCHAR)
}
