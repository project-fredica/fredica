package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenExtractionRunService
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * GET /api/v1/WebenExtractionRunListRoute?source_id=<uuid>&limit=20&offset=0
 *
 * 列出某来源的提取历史，最新在前。
 * 列表只含摘要字段（不含大字段 prompt_script / llm_input_json / llm_output_raw）。
 */
object WebenExtractionRunListRoute : FredicaApi.Route {
    private val logger = createLogger { "WebenExtractionRunListRoute" }

    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "列出某来源的概念提取历史（摘要）"

    @Serializable
    private data class RunSummary(
        val id: String,
        val source_id: String,
        val material_id: String?,
        val llm_model_id: String?,
        val concept_count: Int,
        val created_at: Long,
    )

    @Serializable
    private data class PageResult(
        val items: List<RunSummary>,
        val total: Int,
        val offset: Int,
        val limit: Int,
    )

    override suspend fun handler(param: String): ValidJsonString {
        val query = param.loadJsonModel<Map<String, List<String>>>().getOrElse { emptyMap() }
        val sourceId = query["source_id"]?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: return buildValidJson { kv("error", "source_id 不能为空") }
        val limit = query["limit"]?.firstOrNull()?.toIntOrNull()?.coerceIn(1, 100) ?: 20
        val offset = query["offset"]?.firstOrNull()?.toIntOrNull()?.coerceAtLeast(0) ?: 0

        val items = WebenExtractionRunService.repo.listBySourceId(sourceId, limit, offset)
        val total = WebenExtractionRunService.repo.countBySourceId(sourceId)

        val summaries = items.map { run ->
            RunSummary(
                id            = run.id,
                source_id     = run.sourceId,
                material_id   = run.materialId,
                llm_model_id  = run.llmModelId,
                concept_count = run.conceptCount,
                created_at    = run.createdAt,
            )
        }

        logger.debug("WebenExtractionRunListRoute: sourceId=$sourceId total=$total limit=$limit offset=$offset")
        return ValidJsonString(AppUtil.GlobalVars.json.encodeToString(PageResult(summaries, total, offset, limit)))
    }
}
