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
import com.github.project_fredica.auth.AuthRole

/**
 * GET /api/v1/LlmModelAvailabilityRoute
 *
 * 返回当前 LLM 模型可用性摘要，供 Prompt 工作台判断是否允许“生成”。
 */
object LlmModelAvailabilityRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "获取 LLM 模型可用性摘要"
    override val minRole = AuthRole.GUEST

    private val logger = createLogger { "LlmModelAvailabilityRoute" }

    @Serializable
    data class LlmModelAvailability(
        @SerialName("available_count") val availableCount: Int,
        @SerialName("has_any_available_model") val hasAnyAvailableModel: Boolean,
        @SerialName("selected_model_id") val selectedModelId: String? = null,
        @SerialName("selected_model_available") val selectedModelAvailable: Boolean,
    )

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val query = param.loadJsonModel<Map<String, List<String>>>().getOrElse { emptyMap() }
        val selectedModelId = query["selected_model_id"]?.firstOrNull()?.takeIf { it.isNotBlank() }

        val config = AppConfigService.repo.getConfig()
        val models = config.llmModelsJson.loadJsonModel<List<LlmModelConfig>>().getOrElse { emptyList() }
        val availableIds = models.mapNotNull { it.appModelId.takeIf(String::isNotBlank) }
        val availableCount = availableIds.size
        val selectedModelAvailable = selectedModelId != null && selectedModelId in availableIds

        logger.debug(
            "LlmModelAvailabilityRoute: availableCount=$availableCount selectedModelId=$selectedModelId selectedModelAvailable=$selectedModelAvailable"
        )

        return AppUtil.dumpJsonStr(
            LlmModelAvailability(
                availableCount = availableCount,
                hasAnyAvailableModel = availableCount >= 1,
                selectedModelId = selectedModelId,
                selectedModelAvailable = selectedModelAvailable,
            )
        ).getOrThrow()
    }
}
