package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.readNetworkProxyUrl
import com.github.project_fredica.apputil.warn
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator

/**
 * 读取系统当前检测到的网络代理，返回代理地址字符串。
 *
 * 使用 [AppUtil.readNetworkProxyUrl] 获取地址，该函数通过 InetSocketAddress.getHostString()
 * 直接取主机名，避免 toString() 产生 "<unresolved>" 后缀的问题。
 *
 * JS 调用：
 * ```js
 * callBridge('get_system_proxy', '{}')
 * // => { "proxy_url": "http://127.0.0.1:7890" }  // 检测到代理
 * // => { "proxy_url": "" }                        // 无代理（直连）
 * ```
 */
class GetSystemProxyJsMessageHandler : MyJsMessageHandler() {
    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        val addr = try {
            AppUtil.readNetworkProxyUrl()
        } catch (e: Throwable) {
            logger.warn("[GetSystemProxyJsMessageHandler] failed to read system proxy", isHappensFrequently = false, err = e)
            ""
        }
        if (addr.isBlank()) {
            logger.debug("[GetSystemProxyJsMessageHandler] no system proxy detected")
        } else {
            logger.debug("[GetSystemProxyJsMessageHandler] detected proxy=$addr")
        }
        callback(buildValidJson { kv("proxy_url", addr) }.str)
    }
}
