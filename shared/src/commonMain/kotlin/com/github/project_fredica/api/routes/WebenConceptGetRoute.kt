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
import com.github.project_fredica.db.weben.WebenNoteService
import com.github.project_fredica.db.weben.WebenRelationService
import kotlinx.serialization.encodeToString

/**
 * GET /api/v1/WebenConceptGetRoute?id=<uuid>
 *
 * 概念详情页数据聚合接口，一次请求返回：
 *   - concept        — 概念基础信息（canonical_name / type / brief_definition / mastery 等）
 *   - aliases        — 别名列表（LLM 提取 + 用户手动添加）
 *   - sources        — 来源关联列表（含 timestamp_sec，用于跳转播放器位置）
 *   - relations      — 入边 + 出边关系（用于图谱邻居展示）
 *   - flashcard_count — 关联闪卡数（不返回具体闪卡，避免响应过大）
 *   - notes          — 用户笔记列表（按 updated_at 降序）
 */
object WebenConceptGetRoute : FredicaApi.Route {
    private val logger = createLogger { "WebenConceptGetRoute" }

    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "获取概念详情（含来源关联、关系邻居、闪卡数、笔记）"

    override suspend fun handler(param: String): ValidJsonString {
        val query = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val id    = query["id"]?.firstOrNull()

        // 缺少必填参数：直接返回错误，不查库
        if (id == null) {
            logger.debug("WebenConceptGetRoute: 缺少 id 参数，返回 error")
            return buildValidJson { kv("error", "missing id") }
        }

        logger.debug("WebenConceptGetRoute: 查询概念详情 conceptId=$id")

        val concept = WebenConceptService.repo.getById(id)
        if (concept == null) {
            // 概念不存在（可能已被删除）
            logger.debug("WebenConceptGetRoute: 概念不存在 conceptId=$id，返回 not found")
            return buildValidJson { kv("error", "not found") }
        }

        val aliases    = WebenConceptService.repo.listAliases(id)
        val sources    = WebenConceptService.repo.listSources(id)
        val relations  = WebenRelationService.repo.listByConcept(id)
        val flashcards = WebenFlashcardService.repo.listByConcept(id)
        val notes      = WebenNoteService.repo.listByConcept(id)
        val json       = AppUtil.GlobalVars.json

        logger.debug(
            "WebenConceptGetRoute: 概念详情聚合完成 conceptId=$id" +
            " aliases=${aliases.size} sources=${sources.size}" +
            " relations=${relations.size} flashcards=${flashcards.size} notes=${notes.size}"
        )

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
