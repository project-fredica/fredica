package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.getNativeWebServerLocalDomainAndPort
import com.github.project_fredica.apputil.IntentUtil
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.openBrowser
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import io.ktor.http.URLBuilder
import kotlinx.serialization.Serializable

@Serializable
data class OpenBrowserJsMessageModel(
    val url: String,
    val addServerInfoParam: Boolean,
)

class OpenBrowserJsMessageHandler : MyJsMessageHandler() {
    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit
    ) {
        val logger = createLogger()
        val model = message.params.loadJsonModel<OpenBrowserJsMessageModel>().getOrThrow()
        val b = URLBuilder(model.url)
        if (model.addServerInfoParam) {
            val domainAndPort = FredicaApi.getNativeWebServerLocalDomainAndPort()
            if (domainAndPort !== null) {
                b.parameters["webserver_schema"] = "http"
                b.parameters["webserver_domain"] = domainAndPort.first
                b.parameters["webserver_port"] = domainAndPort.second.toString()
                b.parameters["webserver_auth_token"] = "114514"
            }
        }
        val u = b.build()
        logger.debug("open browser : $u")
        IntentUtil.openBrowser(u.toString())
    }

}