package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.json
import com.github.project_fredica.python.TorchService
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 通过 kmpJsBridge 检查 pip 安装目录中的 torch 下载状态。
 *
 * JS 调用方式：
 * ```js
 * kmpJsBridge.callNative('get_torch_check', JSON.stringify({
 *     expected_version: '2.7.0',  // 可选
 * }), (result) => {
 *     const r = JSON.parse(result);
 *     // r.already_ok        — bool
 *     // r.installed_version — str | null
 *     // r.target_dir        — str
 * });
 * ```
 */
class GetTorchCheckJsMessageHandler : MyJsMessageHandler() {
    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        val params = runCatching {
            AppUtil.GlobalVars.json.parseToJsonElement(message.params) as? JsonObject
        }.getOrNull()
        val expectedVersion = (params?.get("expected_version") as? JsonPrimitive)?.content ?: ""
        callback(TorchService.check(expectedVersion))
    }
}
