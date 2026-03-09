package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenNoteService
import kotlinx.serialization.encodeToString

/**
 * GET /api/v1/WebenNoteListRoute?concept_id=<uuid>
 *
 * 查询某概念的笔记列表（按 updated_at 降序）。
 */
object WebenNoteListRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "查询某概念的笔记列表（按 updated_at 降序）"

    override suspend fun handler(param: String): ValidJsonString {
        val query     = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val conceptId = query["concept_id"]?.firstOrNull() ?: return ValidJsonString("[]")
        val notes     = WebenNoteService.repo.listByConcept(conceptId)
        return ValidJsonString(AppUtil.GlobalVars.json.encodeToString(notes))
    }
}
