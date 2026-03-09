package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenConceptService
import com.github.project_fredica.db.weben.WebenFlashcardService
import kotlinx.serialization.encodeToString

/**
 * GET /api/v1/WebenReviewQueueRoute[?limit=50]
 *
 * 今日待复习闪卡（next_review_at <= now），按到期时间升序，附带所属概念简略信息。
 */
object WebenReviewQueueRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "今日待复习闪卡列表（next_review_at <= now，含概念名称）"

    override suspend fun handler(param: String): ValidJsonString {
        val query     = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val limit     = query["limit"]?.firstOrNull()?.toIntOrNull()?.coerceIn(1, 200) ?: 50
        val nowSec    = System.currentTimeMillis() / 1000L
        val flashcards = WebenFlashcardService.repo.listDueForReview(nowSec, limit)
        val json = AppUtil.GlobalVars.json

        // 附带概念规范名，减少前端额外请求
        val conceptIds = flashcards.map { it.conceptId }.distinct()
        val conceptNames = conceptIds.associateWith { id ->
            WebenConceptService.repo.getById(id)?.canonicalName ?: ""
        }

        return buildValidJson {
            kv("flashcards", ValidJsonString(json.encodeToString(flashcards)))
            kv("concept_names", ValidJsonString(json.encodeToString(conceptNames)))
        }
    }
}
