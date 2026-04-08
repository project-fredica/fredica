package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.sha256Hex
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.db.MaterialVideoService
import com.github.project_fredica.llm.LlmMessagesJson
import com.github.project_fredica.llm.LlmModelConfig
import com.github.project_fredica.llm.LlmRequest
import com.github.project_fredica.llm.LlmRequestServiceHolder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 根据素材标题和简介，用 LLM 猜测视频的主要语言。
 *
 * GET /api/v1/MaterialLanguageGuessRoute?material_id=...
 *
 * 响应：{"language": "zh"} 或 {"language": null}（无 LLM 配置时）
 * LLM 调用异常时直接抛出，由 FredicaApi.jvm.kt 的 handleRouteResult 统一处理，
 * 返回 {"error": "...", "FredicaFixBugAdvice": "OpenClashPAC"}（检测到系统代理时）。
 *
 * 结果缓存到素材媒体目录下的 guess_language.json，
 * 缓存 key 为 prompt 内容的 SHA-256（含标题、简介、提示词模板），输入变化时自动失效。
 */
object MaterialLanguageGuessRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "用 LLM 猜测素材视频的主要语言（ISO 639-1 代码），结果文件缓存到素材目录"

    private val logger = createLogger { "MaterialLanguageGuessRoute" }

    @Serializable
    private data class LanguageGuessCache(
        @SerialName("input_hash") val inputHash: String,
        val language: String?,
    )

    override suspend fun handler(param: String): ValidJsonString {
        val query = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val materialId = query["material_id"]?.firstOrNull()
            ?: return buildJsonObject { put("language", null as String?) }.toValidJson()

        return buildJsonObject { put("language", guessLanguage(materialId)) }.toValidJson()
    }

    private suspend fun guessLanguage(materialId: String): String? {
        val material = MaterialVideoService.repo.findById(materialId) ?: return null

        val title = material.title.take(200)
        val description = material.description.take(300)
        val prompt = "以下是一个视频的标题和简介，请判断视频的主要语言，只返回 ISO 639-1 语言代码（如 zh、en、ja），不要解释。\n标题：$title\n简介：$description"
        val promptHash = sha256Hex(prompt)

        // 检查文件缓存
        val mediaDir = AppUtil.Paths.materialMediaDir(materialId)
        val cacheFile = mediaDir.resolve("guess_language.json")
        if (cacheFile.exists()) {
            val cached = runCatching {
                cacheFile.readText().loadJsonModel<LanguageGuessCache>().getOrThrow()
            }.getOrNull()
            if (cached != null && cached.inputHash == promptHash) {
                logger.debug("[MaterialLanguageGuessRoute] 命中文件缓存 materialId=$materialId lang=${cached.language}")
                return cached.language
            }
        }

        val modelConfig: LlmModelConfig? = run {
            val config = AppConfigService.repo.getConfig()
            config.llmModelsJson.loadJsonModel<List<LlmModelConfig>>()
                .getOrElse { emptyList() }
                .firstOrNull()
        }
        if (modelConfig == null) {
            logger.debug("[MaterialLanguageGuessRoute] 无 LLM 配置，跳过语言猜测 materialId=$materialId")
            return null
        }

        val messagesJson = buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("content", prompt)
            })
        }.toString()

        val resp = LlmRequestServiceHolder.instance.request(
            LlmRequest(
                modelConfig = modelConfig,
                messages = LlmMessagesJson(messagesJson),
                disableCache = false,
            )
        )
        val lang = Regex("[a-z]{2}").find(resp.text.trim().lowercase())?.value
        logger.debug("[MaterialLanguageGuessRoute] materialId=$materialId 猜测结果=${resp.text.trim()} → lang=$lang")
        // 仅 LLM 成功时写入文件缓存
        runCatching {
            val cacheData = LanguageGuessCache(inputHash = promptHash, language = lang)
            cacheFile.writeText(AppUtil.dumpJsonStr(cacheData).getOrThrow().str)
        }.onFailure { e ->
            logger.warn("[MaterialLanguageGuessRoute] 写入缓存失败 materialId=$materialId: ${e.message}")
        }
        return lang
    }
}
