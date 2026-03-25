package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.llm.LlmModelConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GET /api/v1/LlmModelListRoute
 *
 * 返回可供前端安全选择的模型元数据列表。
 * 不暴露 api_key / base_url，仅返回 app_model_id、label、notes。
 */
object LlmModelListRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "获取可用 LLM 模型元数据列表"

    private val logger = createLogger { "LlmModelListRoute" }

    @Serializable
    data class LlmModelMeta(
        @SerialName("app_model_id") val appModelId: String,
        val label: String,
        val notes: String = "",
    )

    override suspend fun handler(param: String): ValidJsonString {
        val config = AppConfigService.repo.getConfig()
        val models = config.llmModelsJson.loadJsonModel<List<LlmModelConfig>>().getOrElse { emptyList() }
        val items = models
            .filter { it.appModelId.isNotBlank() }
            .sortedBy { it.sortOrder }
            .map {
                LlmModelMeta(
                    appModelId = it.appModelId,
                    label = it.name,
                    notes = it.notes,
                )
            }

        logger.debug("LlmModelListRoute: 返回模型数=${items.size}")
        return AppUtil.dumpJsonStr(items).getOrThrow()
    }
}
