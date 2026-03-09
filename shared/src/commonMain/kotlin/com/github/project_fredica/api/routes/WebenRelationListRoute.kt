package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenRelationService
import kotlinx.serialization.encodeToString

/**
 * GET /api/v1/WebenRelationListRoute?concept_id=<uuid>[&direction=both|out|in]
 *
 * 邻居查询。direction：
 *   out  — 以 concept_id 为主体的出边
 *   in   — 以 concept_id 为客体的入边
 *   both — 出边 + 入边（默认）
 */
object WebenRelationListRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "查询概念的关系边（可选方向：out/in/both）"

    override suspend fun handler(param: String): ValidJsonString {
        val query     = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val conceptId = query["concept_id"]?.firstOrNull()
            ?: return ValidJsonString("[]")
        val direction = query["direction"]?.firstOrNull() ?: "both"

        val relations = when (direction) {
            "out"  -> WebenRelationService.repo.listBySubject(conceptId)
            "in"   -> WebenRelationService.repo.listByObject(conceptId)
            else   -> WebenRelationService.repo.listByConcept(conceptId)
        }
        return ValidJsonString(AppUtil.GlobalVars.json.encodeToString(relations))
    }
}
