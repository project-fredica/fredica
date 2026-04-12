package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.asr.service.AsrConfigService
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator

/**
 * 通过 kmpJsBridge 获取 ASR 配置。
 *
 * JS 调用方式：
 * ```js
 * kmpJsBridge.callNative('get_asr_config', '{}', (result) => {
 *     const config = JSON.parse(result);
 * });
 * ```
 */
class GetAsrConfigJsMessageHandler : MyJsMessageHandler() {
    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit
    ) {
        val config = AsrConfigService.getAsrConfig()
        callback(AppUtil.dumpJsonStr(config).getOrThrow().str)
    }
}
