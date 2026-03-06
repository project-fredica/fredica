package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.db.AppConfigService
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator

class GetLlmModelsJsMessageHandler : MyJsMessageHandler() {
    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit
    ) {
        val config = AppConfigService.repo.getConfig()
        callback(config.llmModelsJson)
    }
}
