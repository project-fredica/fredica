package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.*
import com.github.project_fredica.db.LlmResponseCacheService
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.llm.LlmModelConfig
import com.github.project_fredica.llm.LlmMessagesJson
import com.github.project_fredica.llm.LlmCacheKeyUtil
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class LlmCacheInvalidateRequest(
    @SerialName("key_hash") val keyHash: String? = null,
    @SerialName("app_model_id") val appModelId: String? = null,
    @SerialName("messages_json") val messagesJson: String? = null,
)

object LlmCacheInvalidateRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "废除 LLM 响应缓存"

    private val logger = createLogger()

    override suspend fun handler(param: String): ValidJsonString {
        val req = param.loadJsonModel<LlmCacheInvalidateRequest>().getOrElse { e ->
            logger.error("请求体解析失败", e)
            return buildJsonObject { put("error", "invalid request body") }.toValidJson()
        }

        val keyHash = when {
            req.keyHash != null -> req.keyHash
            req.appModelId != null && req.messagesJson != null -> {
                val config = AppConfigService.repo.getConfig()
                val models = config.llmModelsJson.loadJsonModel<List<LlmModelConfig>>().getOrElse { emptyList() }
                val modelConfig = models.find { it.appModelId == req.appModelId }
                    ?: return buildJsonObject { put("error", "model not found") }.toValidJson()

                val cacheKey = LlmCacheKeyUtil.buildCacheKey(
                    modelConfig.model,
                    modelConfig.baseUrl,
                    LlmMessagesJson(req.messagesJson)
                )
                LlmCacheKeyUtil.hashKey(cacheKey)
            }
            else -> return buildJsonObject { put("error", "key_hash or (app_model_id + messages_json) required") }.toValidJson()
        }

        LlmResponseCacheService.repo.invalidate(keyHash)
        return buildJsonObject { put("ok", true) }.toValidJson()
    }
}
