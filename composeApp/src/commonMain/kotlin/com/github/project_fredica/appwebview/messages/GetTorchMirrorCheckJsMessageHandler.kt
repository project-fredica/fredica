package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.get
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.json
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 探测各镜像站对指定 variant 的支持情况。
 *
 * JS 调用：
 * ```js
 * callBridge('get_torch_mirror_check', JSON.stringify({ variant: 'cu126', use_proxy: false, proxy: '' }))
 * // => { "results": [{ "key", "label", "available", "url", "error" }] }
 * ```
 */
class GetTorchMirrorCheckJsMessageHandler : MyJsMessageHandler() {
    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        val params = try {
            AppUtil.GlobalVars.json.parseToJsonElement(message.params) as? JsonObject
        } catch (e: Throwable) {
            logger.warn("[GetTorchMirrorCheckJsMessageHandler] failed to parse params: ${e.message}")
            null
        }

        val variant = params?.get("variant")?.let { it as? JsonPrimitive }?.content ?: ""
        if (variant.isBlank()) {
            callback(buildValidJson { kv("error", "variant is required") }.str)
            return
        }

        val useProxy = params?.get("use_proxy")?.let { it as? JsonPrimitive }?.booleanOrNull ?: false
        val proxy = params?.get("proxy")?.let { it as? JsonPrimitive }?.content ?: ""

        val path = buildString {
            append("/torch/mirror-check?variant=$variant")
            if (useProxy && proxy.isNotBlank()) append("&proxy=$proxy")
        }
        logger.warn("[GetTorchMirrorCheckJsMessageHandler] variant=$variant useProxy=$useProxy path=$path")

        val result = try {
            FredicaApi.PyUtil.get(path)
        } catch (e: Throwable) {
            logger.warn("[GetTorchMirrorCheckJsMessageHandler] Python call failed: ${e.message}")
            buildValidJson { kv("error", e.message ?: "unknown error") }.str
        }
        callback(result)
    }
}
