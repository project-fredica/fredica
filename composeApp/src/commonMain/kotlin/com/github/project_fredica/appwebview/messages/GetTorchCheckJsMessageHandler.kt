package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.get
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.warn
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
        val params = try {
            AppUtil.GlobalVars.json.parseToJsonElement(message.params) as? JsonObject
        } catch (e: Throwable) {
            null
        }
        val expectedVersion = (params?.get("expected_version") as? JsonPrimitive)?.content ?: ""

        val downloadDir = AppUtil.Paths.pipLibDir.absolutePath
        val path = buildString {
            append("/torch/check?download_dir=$downloadDir")
            if (expectedVersion.isNotBlank()) append("&expected_version=$expectedVersion")
        }
        logger.warn("[GetTorchCheckJsMessageHandler] path=$path")
        val result = try {
            FredicaApi.PyUtil.get(path)
        } catch (e: Throwable) {
            logger.warn("[GetTorchCheckJsMessageHandler] failed", isHappensFrequently = false, err = e)
            buildValidJson { kv("error", e.message ?: "unknown") }.str
        }
        callback(result)
    }
}
