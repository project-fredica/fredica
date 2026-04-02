package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenExtractionRunService
import kotlinx.serialization.encodeToString

/**
 * GET /api/v1/WebenExtractionRunGetRoute?id=<uuid>
 *
 * 获取单次提取运行的完整详情，包括 prompt_text 和 llm_output_raw。
 */
object WebenExtractionRunGetRoute : FredicaApi.Route {
    private val logger = createLogger { "WebenExtractionRunGetRoute" }

    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "获取单次概念提取运行详情（含 prompt/output）"

    override suspend fun handler(param: String): ValidJsonString {
        val query = param.loadJsonModel<Map<String, List<String>>>().getOrElse { emptyMap() }
        val id = query["id"]?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: return buildValidJson { kv("error", "id 不能为空") }

        val run = WebenExtractionRunService.repo.getById(id)
            ?: return buildValidJson { kv("error", "提取记录不存在: $id") }

        logger.debug("WebenExtractionRunGetRoute: id=$id")
        return ValidJsonString(AppUtil.GlobalVars.json.encodeToString(run))
    }
}
