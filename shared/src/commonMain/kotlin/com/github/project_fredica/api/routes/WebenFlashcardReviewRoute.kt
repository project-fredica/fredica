package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenFlashcardService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST /api/v1/WebenFlashcardReviewRoute
 *
 * 提交单张闪卡评分。在事务内完成 SM-2 更新、历史快照写入、concept.mastery 缓存重算。
 *
 * review_type:
 *   'view'   — 浏览概念卡片，rating 可省略（不更新 SM-2 状态，只记录历史）
 *   'quiz'   — 主动测验，rating 0-5 必填
 *   'manual' — 用户手动调分，rating 0-5 必填
 */
object WebenFlashcardReviewRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "提交闪卡复习评分（SM-2 更新 + mastery 缓存同步）"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<WebenFlashcardReviewParam>().getOrThrow()
        val nowSec = System.currentTimeMillis() / 1000L
        WebenFlashcardService.repo.review(
            flashcardId = p.flashcardId,
            rating      = p.rating,
            reviewType  = p.reviewType,
            nowSeconds  = nowSec,
        )
        return buildValidJson { kv("ok", true) }
    }
}

@Serializable
data class WebenFlashcardReviewParam(
    @SerialName("flashcard_id") val flashcardId: String,
    /** SM-2 评分 0-5；view 类型可不传（null）。 */
    val rating: Int? = null,
    @SerialName("review_type") val reviewType: String = "quiz",
)
