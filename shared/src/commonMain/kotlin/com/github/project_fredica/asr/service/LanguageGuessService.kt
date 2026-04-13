package com.github.project_fredica.asr.service

import com.github.project_fredica.apputil.AppUtil
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
 * 用 LLM 猜测素材视频的主要语言。
 *
 * 结果缓存到素材媒体目录下的 `guess_language.json`，
 * 缓存 key 为 prompt 内容的 SHA-256（含标题、简介、提示词模板），输入变化时自动失效。
 */
object LanguageGuessService {

    private val logger = createLogger { "LanguageGuessService" }

    @Serializable
    data class LanguageGuessCache(
        @SerialName("input_hash") val inputHash: String,
        val language: String?,
    )

    private val PROMPT_TEMPLATE = """
你是一个语言识别助手。根据以下视频的标题和简介，判断视频中**实际说的语言**（而非标题/简介的书写语言）。

规则：
1. 如果标题和简介明确表明视频内容的语言（如“英语教学”、“日语歌曲”），返回对应的 ISO 639-1 代码（如 en、ja）。
2. 如果视频可能是搬运、配音、翻译、或多语言内容（如标题是中文但内容可能是外语、“生肉”），返回 auto。
   但如果写明了配音是什么语言（例如“中配”、“熟肉”）则你可以大胆猜测语言为平台受众群体的主要语言。
3. 如果无法从标题和简介判断实际语言，返回 auto。
4. 只返回一个 ISO 639-1 语言代码或 auto，不要解释。

标题：{title}
简介：{description}
    """.trimIndent()

    /**
     * 猜测素材视频的主要语言。
     *
     * @return ISO 639-1 语言代码（如 "zh"、"en"）、"auto"（不确定时）、或 null（无 LLM 配置时）
     */
    suspend fun guessLanguage(materialId: String): String? {
        val material = MaterialVideoService.repo.findById(materialId) ?: return null

        val title = material.title.take(200)
        val description = material.description.take(300)
        val prompt = PROMPT_TEMPLATE.replace("{title}", title).replace("{description}", description)
        val promptHash = sha256Hex(prompt)

        // 检查文件缓存
        val mediaDir = AppUtil.Paths.materialMediaDir(materialId)
        val cacheFile = mediaDir.resolve("guess_language.json")
        if (cacheFile.exists()) {
            val cached = runCatching {
                cacheFile.readText().loadJsonModel<LanguageGuessCache>().getOrThrow()
            }.getOrNull()
            if (cached != null && cached.inputHash == promptHash) {
                logger.debug("命中文件缓存 materialId=$materialId lang=${cached.language}")
                return cached.language
            }
        }

        val modelConfig: LlmModelConfig? = run {
            val config = AppConfigService.repo.getConfig()
            config.llmModelsJson.loadJsonModel<List<LlmModelConfig>>().getOrElse { emptyList() }.firstOrNull()
        }
        if (modelConfig == null) {
            logger.debug("无 LLM 配置，跳过语言猜测 materialId=$materialId")
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
        val lang = Regex("(auto|[a-z]{2})").find(resp.text.trim().lowercase())?.value
        logger.info("materialId=$materialId 猜测结果=${resp.text.trim()} → lang=$lang")

        // 仅 LLM 成功时写入文件缓存
        runCatching {
            val cacheData = LanguageGuessCache(inputHash = promptHash, language = lang)
            cacheFile.writeText(AppUtil.dumpJsonStr(cacheData).getOrThrow().str)
        }.onFailure { e ->
            logger.warn("写入缓存失败 materialId=$materialId: ${e.message}")
        }
        return lang
    }
}
