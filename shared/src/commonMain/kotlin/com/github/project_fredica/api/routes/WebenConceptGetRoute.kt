package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenConceptService
import com.github.project_fredica.db.weben.WebenNoteService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import com.github.project_fredica.auth.AuthRole
import kotlinx.serialization.json.put

/**
 * GET /api/v1/WebenConceptGetRoute?id=<uuid>
 *
 * 概念详情页数据聚合接口，一次请求返回：
 *   - concept        — 概念基础信息（canonical_name / type / brief_definition 等）
 *   - aliases        — 别名列表（LLM 提取 + 用户手动添加）
 *   - sources        — 来源关联列表（含 timestamp_sec，用于跳转播放器位置）
 *   - notes          — 用户笔记列表（按 updated_at 降序）
 */
object WebenConceptGetRoute : FredicaApi.Route {
    private val logger = createLogger { "WebenConceptGetRoute" }

    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "获取概念详情（含来源关联、笔记）"
    override val minRole = AuthRole.GUEST

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val query = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val id    = query["id"]?.firstOrNull()

        if (id == null) {
            logger.debug("WebenConceptGetRoute: 缺少 id 参数，返回 error")
            return buildJsonObject { put("error", "missing id") }.toValidJson()
        }

        logger.debug("WebenConceptGetRoute: 查询概念详情 conceptId=$id")

        val concept = WebenConceptService.repo.getById(id)
        if (concept == null) {
            logger.debug("WebenConceptGetRoute: 概念不存在 conceptId=$id，返回 not found")
            return buildJsonObject { put("error", "not found") }.toValidJson()
        }

        val aliases = WebenConceptService.repo.listAliases(id)
        val sources = WebenConceptService.repo.listSources(id)
        val notes   = WebenNoteService.repo.listByConcept(id)
        val json    = AppUtil.GlobalVars.json

        logger.debug(
            "WebenConceptGetRoute: 概念详情聚合完成 conceptId=$id" +
            " aliases=${aliases.size} sources=${sources.size} notes=${notes.size}"
        )

        return buildJsonObject {
            put("concept",  ValidJsonString(json.encodeToString(concept)).toJsonElement())
            put("aliases",  ValidJsonString(json.encodeToString(aliases)).toJsonElement())
            put("sources",  ValidJsonString(json.encodeToString(sources)).toJsonElement())
            put("notes",    ValidJsonString(json.encodeToString(notes)).toJsonElement())
        }.toValidJson()
    }
}
