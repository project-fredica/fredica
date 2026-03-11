package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenConceptService
import com.github.project_fredica.db.weben.WebenFlashcardService
import kotlinx.serialization.encodeToString

/**
 * GET /api/v1/WebenReviewQueueRoute[?limit=50]
 *
 * 今日待复习闪卡（next_review_at <= now），按到期时间升序（最久未复习的排在最前），
 * 附带所属概念的 canonical_name，减少前端额外请求。
 *
 * SM-2 算法：每次评分后更新 next_review_at，到期时间由间隔因子动态计算。
 * 本接口直接按 next_review_at 过滤，不含未到期卡片。
 */
object WebenReviewQueueRoute : FredicaApi.Route {
    private val logger = createLogger { "WebenReviewQueueRoute" }

    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "今日待复习闪卡列表（next_review_at <= now，含概念名称）"

    override suspend fun handler(param: String): ValidJsonString {
        val query  = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val limit  = query["limit"]?.firstOrNull()?.toIntOrNull()?.coerceIn(1, 200) ?: 50
        val nowSec = System.currentTimeMillis() / 1000L

        logger.debug("WebenReviewQueueRoute: 查询待复习队列 limit=$limit nowSec=$nowSec")

        val flashcards = WebenFlashcardService.repo.listDueForReview(nowSec, limit)

        logger.debug("WebenReviewQueueRoute: 待复习闪卡 ${flashcards.size} 张（limit=$limit）")

        // 批量查 concept canonical_name，附带到响应中，避免前端逐条请求 WebenConceptGetRoute
        val conceptIds   = flashcards.map { it.conceptId }.distinct()
        val conceptNames = conceptIds.associateWith { id ->
            WebenConceptService.repo.getById(id)?.canonicalName ?: ""
        }

        logger.debug(
            "WebenReviewQueueRoute: 关联概念 ${conceptNames.size} 个（去重后）" +
            " 共 ${flashcards.size} 张闪卡"
        )

        val json = AppUtil.GlobalVars.json
        return buildValidJson {
            kv("flashcards",    ValidJsonString(json.encodeToString(flashcards)))
            kv("concept_names", ValidJsonString(json.encodeToString(conceptNames)))
        }
    }
}
