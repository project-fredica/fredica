package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenFlashcard
import com.github.project_fredica.db.weben.WebenFlashcardService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST /api/v1/WebenFlashcardCreateRoute
 *
 * 用户手动创建闪卡（is_system=false）。
 * next_review_at 默认设为 now（立即可复习）。
 */
object WebenFlashcardCreateRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "用户手动创建闪卡（is_system=false）"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<WebenFlashcardCreateParam>().getOrThrow()
        val nowSec = System.currentTimeMillis() / 1000L
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
        return buildValidJson { kv("ok", true); kv("id", card.id) }
    }
}

@Serializable
data class WebenFlashcardCreateParam(
    @SerialName("concept_id") val conceptId: String,
    @SerialName("source_id")  val sourceId:  String? = null,
    val question: String,
    val answer: String,
    @SerialName("card_type") val cardType: String? = null,
)
