package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenConcept
import com.github.project_fredica.db.weben.WebenConceptService
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * GET /api/v1/WebenConceptListRoute[?source_id=&concept_type=&limit=50&offset=0]
 *
 * 概念分页查询。按掌握度升序（掌握度低的优先展示）。
 *
 * 参数说明：
 *   source_id    — 过滤来源（WebenSource.id），只返回该来源提取的概念
 *   concept_type — 过滤概念类型（如"术语"/"算法"/"定理"），不传则返回全部
 *   limit        — 每页条数，范围 1-200，默认 50
 *   offset       — 分页偏移，默认 0
 *
 * 响应：
 * ```json
 * {"items": [...], "total": 42, "offset": 0, "limit": 50}
 * ```
 */
object WebenConceptListRoute : FredicaApi.Route {
    private val logger = createLogger { "WebenConceptListRoute" }

    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "概念分页查询（可按 source_id/concept_type 过滤，按掌握度升序）"

    @Serializable
    private data class PageResult(
        val items: List<WebenConcept>,
        val total: Int,
        val offset: Int,
        val limit: Int,
    )

    override suspend fun handler(param: String): ValidJsonString {
        val query       = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val sourceId    = query["source_id"]?.firstOrNull()
        val conceptType = query["concept_type"]?.firstOrNull()
        val limit       = query["limit"]?.firstOrNull()?.toIntOrNull()?.coerceIn(1, 200) ?: 50
        val offset      = query["offset"]?.firstOrNull()?.toIntOrNull()?.coerceAtLeast(0) ?: 0

        val concepts = WebenConceptService.repo.listAll(
            conceptType = conceptType,
            sourceId    = sourceId,
            limit       = limit,
            offset      = offset,
        )
        val total = WebenConceptService.repo.count(
            conceptType = conceptType,
            sourceId    = sourceId,
        )

        logger.debug("WebenConceptListRoute: sourceId=$sourceId conceptType=$conceptType total=$total limit=$limit offset=$offset")
        return ValidJsonString(AppUtil.GlobalVars.json.encodeToString(PageResult(concepts, total, offset, limit)))
    }
}
