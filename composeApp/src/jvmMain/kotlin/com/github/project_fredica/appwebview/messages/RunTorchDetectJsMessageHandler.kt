package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.python.TorchService
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator

/**
 * 通过 kmpJsBridge 重新探测 GPU，更新 torch 推荐版本。
 *
 * JS 调用方式：
 * ```js
 * kmpJsBridge.callNative('run_torch_detect', '{}', (result) => {
 *     const r = JSON.parse(result);
 *     // r.torch_recommended_variant  — 最新推荐 variant
 *     // r.torch_recommendation_json  — 完整推荐 JSON
 *     // r.error                      — 错误信息（可选）
 * });
 * ```
 */
class RunTorchDetectJsMessageHandler : MyJsMessageHandler() {
    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        callback(TorchService.runDetect())
    }
}
