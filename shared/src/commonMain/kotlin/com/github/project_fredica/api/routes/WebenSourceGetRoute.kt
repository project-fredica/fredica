package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenConceptService
import com.github.project_fredica.db.weben.WebenSourceService
import kotlinx.serialization.encodeToString

/**
 * GET /api/v1/WebenSourceGetRoute?id=<source_uuid>
 *
 * 查询单条来源详情，含聚合的 concept_count。
 * 来源不存在时返回 JSON `null`。
 */
object WebenSourceGetRoute : FredicaApi.Route {
    private val logger = createLogger { "WebenSourceGetRoute" }

    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "查询单条来源详情（含概念数）"

    override suspend fun handler(param: String): ValidJsonString {
        val query    = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val sourceId = query["id"]?.firstOrNull()
            ?: return ValidJsonString("null").also { logger.debug("WebenSourceGetRoute: 缺少 id 参数") }

        val source = WebenSourceService.repo.getById(sourceId)
            ?: return ValidJsonString("null").also { logger.debug("WebenSourceGetRoute: 来源 id=$sourceId 不存在") }

        val conceptCount = runCatching { WebenConceptService.repo.count(sourceId = sourceId) }.getOrElse { 0 }
        val item = WebenSourceListRoute.WebenSourceListItem(source, conceptCount)
        logger.debug("WebenSourceGetRoute: id=$sourceId conceptCount=$conceptCount")
        return ValidJsonString(AppUtil.GlobalVars.json.encodeToString(item))
    }
}
