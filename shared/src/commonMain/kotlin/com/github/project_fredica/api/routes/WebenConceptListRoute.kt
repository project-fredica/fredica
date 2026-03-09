package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenConceptService
import kotlinx.serialization.encodeToString

/**
 * GET /api/v1/WebenConceptListRoute[?concept_type=&limit=50&offset=0]
 *
 * 概念瀑布流分页。按掌握度升序（掌握度低的优先展示）。
 * concept_type 为空时返回全部类型。
 */
object WebenConceptListRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "概念瀑布流分页（可按 concept_type 过滤，按掌握度升序）"

    override suspend fun handler(param: String): ValidJsonString {
        val query       = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val conceptType = query["concept_type"]?.firstOrNull()
        val limit       = query["limit"]?.firstOrNull()?.toIntOrNull()?.coerceIn(1, 200) ?: 50
        val offset      = query["offset"]?.firstOrNull()?.toIntOrNull()?.coerceAtLeast(0) ?: 0

        val concepts = WebenConceptService.repo.listAll(
            conceptType = conceptType,
            limit       = limit,
            offset      = offset,
        )
        return ValidJsonString(AppUtil.GlobalVars.json.encodeToString(concepts))
    }
}
