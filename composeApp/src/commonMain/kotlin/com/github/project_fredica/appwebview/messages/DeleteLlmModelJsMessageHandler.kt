package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.llm.LlmModelConfig
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DeleteLlmModelJsMessageHandler : MyJsMessageHandler() {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit
    ) {
        val p = message.params.loadJsonModel<DeleteParam>().getOrThrow()
        val config = AppConfigService.repo.getConfig()
        val models = json.decodeFromString<List<LlmModelConfig>>(config.llmModelsJson)
            .filter { it.id != p.id }
        AppConfigService.repo.updateConfig(config.copy(llmModelsJson = json.encodeToString(models)))
        callback(json.encodeToString(models))
    }

    @Serializable
    private data class DeleteParam(@SerialName("id") val id: String)
}
