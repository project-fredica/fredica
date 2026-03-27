package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.llm.LlmCapability
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object LlmModelProbeRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "探测 LLM 模型功能参数"

    private val logger = createLogger { "LlmModelProbeRoute" }

    @Serializable
    data class ProbeParam(
        @SerialName("provider_type") val providerType: String = "OPENAI_COMPATIBLE",
        @SerialName("base_url") val baseUrl: String,
        @SerialName("api_key") val apiKey: String,
        val model: String,
    )

    @Serializable
    data class ProbeResp(
        @SerialName("provider_confirmed") val providerConfirmed: Boolean,
        @SerialName("model_exists") val modelExists: Boolean,
        val capabilities: List<String>,
        @SerialName("context_window") val contextWindow: Int? = null,
        @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
        val temperature: Double? = null,
        val warnings: List<String> = emptyList(),
        @SerialName("provider_notes") val providerNotes: String? = null,
        val error: String? = null,
    )

    override suspend fun handler(param: String): ValidJsonString {
        return try {
            val p = param.loadJsonModel<ProbeParam>().getOrThrow()
            val warnings = mutableListOf<String>()
            val normalizedBaseUrl = p.baseUrl.trimEnd('/')
            logger.debug("[probe] start model=${p.model} baseUrl=$normalizedBaseUrl")
            val body = AppUtil.GlobalVars.ktorClientProxied.get("$normalizedBaseUrl/models") {
                header(HttpHeaders.Authorization, "Bearer ${p.apiKey}")
            }.bodyAsText()
            logger.debug("[probe] /models response len=${body.length}")
            val root = AppUtil.GlobalVars.json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonArray.orEmpty()
            logger.debug("[probe] /models returned ${data.size} entries")
            val matched = data.firstOrNull {
                it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == p.model
            }?.jsonObject

            if (matched == null) {
                warnings += "provider /models 未找到该模型，以下结果仅基于模型名推断。"
                logger.debug("[probe] model=${p.model} not found in /models, inferring from name")
            } else {
                logger.debug("[probe] model=${p.model} found in /models entry=$matched")
            }

            val caps = inferCapabilities(p.model, matched)
            val contextWindow = matched.findInt("context_window") ?: inferContextWindowFromName(p.model)
            val maxOutput = matched.findInt("max_output_tokens")
            val temperature = matched.findDouble("default_temperature")
            logger.debug("[probe] inferred caps=${caps.map { it.name }} contextWindow=$contextWindow maxOutput=$maxOutput temperature=$temperature")
            val resp = ProbeResp(
                providerConfirmed = true,
                modelExists = matched != null,
                capabilities = caps.map { it.name },
                contextWindow = contextWindow,
                maxOutputTokens = maxOutput,
                temperature = temperature,
                warnings = warnings,
                providerNotes = matched?.toString(),
            )
            logger.info("[probe] done model=${p.model} modelExists=${resp.modelExists} caps=${resp.capabilities} contextWindow=${resp.contextWindow}")
            AppUtil.dumpJsonStr(resp).getOrThrow()
        } catch (e: Throwable) {
            logger.warn("[probe] failed", isHappensFrequently = false, err = e)
            buildValidJson { kv("error", e.message ?: "probe failed") }
        }
    }

    private fun inferCapabilities(modelName: String, matched: JsonObject?): List<LlmCapability> {
        val lower = modelName.lowercase()
        val result = mutableSetOf<LlmCapability>()
        if (listOf("vision", "vl", "gpt-4o", "gemini", "claude").any(lower::contains)) result += LlmCapability.VISION
        if (listOf("tool", "func", "gpt", "claude", "qwen", "glm").any(lower::contains)) result += LlmCapability.FUNCTION_CALLING
        if (listOf("json", "gpt", "claude", "qwen", "glm").any(lower::contains)) result += LlmCapability.JSON_SCHEMA
        if ((matched.findInt("context_window") ?: 0) >= 128000 || listOf("128k", "200k", "long").any(lower::contains)) result += LlmCapability.LONG_CONTEXT
        if (lower.contains("mcp")) result += LlmCapability.MCP
        return result.toList()
    }

    private fun inferContextWindowFromName(modelName: String): Int? {
        val lower = modelName.lowercase()
        return when {
            lower.contains("200k") -> 200_000
            lower.contains("128k") -> 128_000
            lower.contains("64k") -> 64_000
            lower.contains("32k") -> 32_000
            else -> null
        }
    }

    private fun JsonObject?.findInt(key: String): Int? = this?.get(key)?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    private fun JsonObject?.findDouble(key: String): Double? = this?.get(key)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
}