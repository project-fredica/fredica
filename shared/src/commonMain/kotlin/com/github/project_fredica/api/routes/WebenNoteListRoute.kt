package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenNoteService
import kotlinx.serialization.encodeToString

/**
 * GET /api/v1/WebenNoteListRoute?concept_id=<uuid>
 *
 * 查询某概念的笔记列表（按 updated_at 降序，最近编辑的排在最前）。
 */
object WebenNoteListRoute : FredicaApi.Route {
    private val logger = createLogger { "WebenNoteListRoute" }

    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "查询某概念的笔记列表（按 updated_at 降序）"

    override suspend fun handler(param: String): ValidJsonString {
        val query     = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val conceptId = query["concept_id"]?.firstOrNull()

        // concept_id 为必填；缺失时返回空数组
        if (conceptId == null) {
            logger.debug("WebenNoteListRoute: 缺少 concept_id 参数，返回空数组")
            return ValidJsonString("[]")
        }

        logger.debug("WebenNoteListRoute: 查询笔记列表 conceptId=$conceptId")
        val notes = WebenNoteService.repo.listByConcept(conceptId)
        logger.debug("WebenNoteListRoute: 返回 ${notes.size} 条笔记 conceptId=$conceptId")
        return ValidJsonString(AppUtil.GlobalVars.json.encodeToString(notes))
    }
}
