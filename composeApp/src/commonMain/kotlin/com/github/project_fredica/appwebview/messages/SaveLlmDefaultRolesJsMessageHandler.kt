package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.llm.LlmDefaultRoles
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SaveLlmDefaultRolesJsMessageHandler : MyJsMessageHandler() {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit
    ) {
        val roles = message.params.loadJsonModel<LlmDefaultRoles>().getOrThrow()
        val config = AppConfigService.repo.getConfig()
        AppConfigService.repo.updateConfig(config.copy(llmDefaultRolesJson = json.encodeToString(roles)))
        callback(json.encodeToString(roles))
    }
}
