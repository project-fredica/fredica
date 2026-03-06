package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import com.github.project_fredica.api.getNativeWebServerLocalDomainAndPort

/**
 * JsBridge：获取本地 Ktor 服务器连接信息，供 WebView 内的 appConfig 初始化使用。
 *
 * JS 调用方式：
 * ```js
 * kmpJsBridge.callNative('get_server_info', '{}', (result) => {
 *     const info = JSON.parse(result);
 *     // info.webserver_schema, info.webserver_domain, info.webserver_port, info.webserver_auth_token
 * });
 * ```
 */
class GetServerInfoJsMessageHandler : MyJsMessageHandler() {
    override val logger = createLogger()

    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        val domainAndPort = FredicaApi.getNativeWebServerLocalDomainAndPort()
        callback(buildValidJson {
            kv("webserver_schema", "http")
            kv("webserver_domain", domainAndPort?.first ?: "localhost")
            kv("webserver_port", domainAndPort?.second?.toString() ?: "7631")
            kv("webserver_auth_token", "114514")
        }.str)
    }
}
