package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.getNativeWebServerLocalDomainAndPort
import com.github.project_fredica.apputil.IntentUtil
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.openBrowser
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.Serializable

/**
 * Bridge 方法名：open_internal_tab
 *
 * 在系统浏览器中打开指定的应用内路径（Phase 1 实现）。
 * Phase 6 升级为在 Compose 新窗口中打开内嵌 WebView。
 *
 * 参数：
 * ```json
 * {"path": "/material/xxx/subtitle-bilibili"}
 * ```
 *
 * 响应：`{}`（fire-and-forget，结果无意义）
 */
@Serializable
data class OpenInternalTabJsMessageModel(
    val path: String,
)

class OpenInternalTabJsMessageHandler : MyJsMessageHandler() {
    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit
    ) {
        val logger = createLogger()
        val model = message.params.loadJsonModel<OpenInternalTabJsMessageModel>().getOrThrow()
        val domainAndPort = FredicaApi.getNativeWebServerLocalDomainAndPort()
        val url = if (domainAndPort != null) {
            "http://${domainAndPort.first}:${domainAndPort.second}${model.path}"
        } else {
            model.path
        }
        logger.debug("open internal tab: $url")
        IntentUtil.openBrowser(url)
        callback(buildValidJson { }.str)
    }
}
