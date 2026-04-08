package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.createLogger
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.github.project_fredica.apputil.error
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.llm.LlmModelConfig
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SaveLlmModelJsMessageHandler : MyJsMessageHandler() {
    override val logger = createLogger()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit
    ) {
        val incoming = message.params.loadJsonModel<LlmModelConfig>().getOrThrow()
        val config = AppConfigService.repo.getConfig()
        val models = json.decodeFromString<List<LlmModelConfig>>(config.llmModelsJson).toMutableList()

        // 若 appModelId 为空，自动生成唯一标识（模型名 slug + 随机 6 位字母数字后缀）
        val existingAppModelIds = models.map { it.appModelId }.toSet()
        val withAppModelId = if (incoming.appModelId.isBlank()) {
            incoming.copy(appModelId = generateUniqueAppModelId(incoming.model, existingAppModelIds))
        } else {
            // 检查 appModelId 唯一性（排除自身）
            val conflict = models.any { it.appModelId == incoming.appModelId && it.id != incoming.id }
            if (conflict) {
                logger.error("SaveLlmModelJsMessageHandler: appModelId 冲突 appModelId=${incoming.appModelId}")
                callback(buildJsonObject { put("error", "appModelId '${incoming.appModelId}' 已被其他模型使用") }.toString())
                return
            }
            incoming
        }

        val idx = models.indexOfFirst { it.id == withAppModelId.id }
        if (idx >= 0) models[idx] = withAppModelId else models.add(withAppModelId)
        AppConfigService.repo.updateConfig(config.copy(llmModelsJson = json.encodeToString(models)))
        callback(json.encodeToString(models))
    }

    private fun generateUniqueAppModelId(modelName: String, existing: Set<String>): String {
        // 将模型名转为 slug（小写字母数字，其余替换为 -，去掉首尾 -）
        val slug = modelName.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(24)
            .ifEmpty { "model" }
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        repeat(20) {
            val suffix = (1..6).map { chars.random() }.joinToString("")
            val candidate = "$slug-$suffix"
            if (candidate !in existing) return candidate
        }
        // 极端情况：用 UUID 后 8 位兜底
        return "$slug-${java.util.UUID.randomUUID().toString().takeLast(8)}"
    }
}
