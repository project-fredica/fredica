package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenFlashcard
import com.github.project_fredica.db.weben.WebenFlashcardService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST /api/v1/WebenFlashcardCreateRoute
 *
 * 用户手动创建闪卡（is_system=false）。
 * next_review_at 默认设为 now（立即加入复习队列）。
 *
 * card_type 枚举：
 *   qa      — 问答卡（默认）
 *   cloze   — 填空卡
 *   concept — 概念定义卡
 */
object WebenFlashcardCreateRoute : FredicaApi.Route {
    private val logger = createLogger { "WebenFlashcardCreateRoute" }

    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "用户手动创建闪卡（is_system=false）"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<WebenFlashcardCreateParam>().getOrThrow()
        val nowSec = System.currentTimeMillis() / 1000L

        logger.debug(
            "WebenFlashcardCreateRoute: 创建闪卡 conceptId=${p.conceptId}" +
            " cardType=${p.cardType ?: "qa"} sourceId=${p.sourceId}"
        )

        val card = WebenFlashcard(
            id           = java.util.UUID.randomUUID().toString(),
            conceptId    = p.conceptId,
            sourceId     = p.sourceId,
            question     = p.question,
            answer       = p.answer,
            cardType     = p.cardType ?: "qa",
            isSystem     = false,
            nextReviewAt = nowSec,
            createdAt    = nowSec,
        )
        WebenFlashcardService.repo.create(card)

        logger.info(
            "WebenFlashcardCreateRoute: 闪卡已创建 id=${card.id}" +
            " conceptId=${p.conceptId} cardType=${card.cardType}"
        )
        return buildValidJson { kv("ok", true); kv("id", card.id) }
    }
}

@Serializable
data class WebenFlashcardCreateParam(
    @SerialName("concept_id") val conceptId: String,
    /** 关联来源 id（可选，用于定位视频片段）。 */
    @SerialName("source_id")  val sourceId:  String? = null,
    val question: String,
    val answer: String,
    /** 卡片类型：qa（默认）/ cloze / concept。 */
    @SerialName("card_type") val cardType: String? = null,
)
