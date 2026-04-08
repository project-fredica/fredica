package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.CaseFormat
import com.github.project_fredica.apputil.convertCase
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.warn
import com.multiplatform.webview.jsbridge.IJsMessageHandler
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

abstract class MyJsMessageHandler : IJsMessageHandler {
    protected open val logger = createLogger()

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
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handle2(message, navigator) {
                    callback(
                        // kmpJsBridge 将回调字符串包裹在 JS 单引号字面量中：
                        //   window.kmpJsBridge.onCallback(id, '<payload>')
                        // 因此 payload 内的反斜杠和单引号必须提前转义，否则会破坏 JS 语法，
                        // 导致前端 JSON.parse 收到截断或错误的字符串。
                        // 顺序：先转义反斜杠（避免后续步骤二次转义），再转义单引号。
                        it.replace("\\", "\\\\").replace("'", "\\'")
                    )
                }
            } catch (e: Throwable) {
                logger.warn("[${this@MyJsMessageHandler.javaClass.simpleName}] unhandled error", isHappensFrequently = false, err = e)
                callback(buildJsonObject { put("error", e.message ?: "unknown error") }.toString())
            }
        }
    }

    abstract suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit
    )
}