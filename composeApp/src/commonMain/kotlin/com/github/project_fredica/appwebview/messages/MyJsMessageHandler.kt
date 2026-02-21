package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.CaseFormat
import com.github.project_fredica.apputil.CreateJsonUtil
import com.github.project_fredica.apputil.asT
import com.github.project_fredica.apputil.convertCase
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJson
import com.github.project_fredica.apputil.loadJsonModel
import com.multiplatform.webview.jsbridge.IJsMessageHandler
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

abstract class MyJsMessageHandler : IJsMessageHandler {
    protected val logger = createLogger()

    override fun toString(): String {
        return this.javaClass.simpleName
    }

    final override fun methodName(): String {
        return this.javaClass.simpleName.let {
            if (it.endsWith("JsMessageHandler")) {
                it.removeSuffix("JsMessageHandler")
            } else {
                throw IllegalStateException("Invalid JsMessageHandler class name")
            }.convertCase(CaseFormat.UPPER_CAMEL, CaseFormat.LOWER_UNDERSCORE)
        }
    }

    final override fun handle(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit
    ) {
        logger.debug("[${this.javaClass.simpleName}] handle js message: $message")
        runBlocking(Dispatchers.IO) {
            handle2(message, navigator, callback)
        }
    }

    abstract suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit
    )
}