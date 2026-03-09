package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenConceptService
import com.github.project_fredica.db.weben.WebenFlashcardService
import com.github.project_fredica.db.weben.WebenNoteService
import com.github.project_fredica.db.weben.WebenRelationService
import kotlinx.serialization.encodeToString

/**
 * GET /api/v1/WebenConceptGetRoute?id=<uuid>
 *
 * 概念详情：含来源列表、入/出关系边、关联闪卡数、笔记列表。
 */
object WebenConceptGetRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "获取概念详情（含来源关联、关系邻居、闪卡数、笔记）"

    override suspend fun handler(param: String): ValidJsonString {
        val query = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val id    = query["id"]?.firstOrNull() ?: return buildValidJson { kv("error", "missing id") }

        val concept    = WebenConceptService.repo.getById(id)
            ?: return buildValidJson { kv("error", "not found") }
        val aliases    = WebenConceptService.repo.listAliases(id)
        val sources    = WebenConceptService.repo.listSources(id)
        val relations  = WebenRelationService.repo.listByConcept(id)
        val flashcards = WebenFlashcardService.repo.listByConcept(id)
        val notes      = WebenNoteService.repo.listByConcept(id)
        val json       = AppUtil.GlobalVars.json

        return buildValidJson {
            kv("concept",         ValidJsonString(json.encodeToString(concept)))
            kv("aliases",         ValidJsonString(json.encodeToString(aliases)))
            kv("sources",         ValidJsonString(json.encodeToString(sources)))
            kv("relations",       ValidJsonString(json.encodeToString(relations)))
            kv("flashcard_count", flashcards.size)
            kv("notes",           ValidJsonString(json.encodeToString(notes)))
        }
    }
}
