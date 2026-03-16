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
import kotlinx.serialization.json.booleanOrNull

/**
 * 抓取指定镜像站支持的 torch variant 列表。
 *
 * JS 调用：
 * ```js
 * callBridge('get_torch_mirror_versions', JSON.stringify({ mirror_key: 'nju', use_proxy: false, proxy: '' }))
 * // => { "variants": ["cu128", "cu126", ...], "error": "" }
 * ```
 */
class GetTorchMirrorVersionsJsMessageHandler : MyJsMessageHandler() {
    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        val params = try {
            AppUtil.GlobalVars.json.parseToJsonElement(message.params) as? JsonObject
        } catch (e: Throwable) {
            logger.warn("[GetTorchMirrorVersionsJsMessageHandler] failed to parse params", isHappensFrequently = false, err = e)
            null
        }

        val mirrorKey = params?.get("mirror_key")?.let { it as? JsonPrimitive }?.content ?: ""
        if (mirrorKey.isBlank()) {
            callback(buildValidJson { kv("error", "mirror_key is required") }.str)
            return
        }

        val useProxy = params?.get("use_proxy")?.let { it as? JsonPrimitive }?.booleanOrNull ?: false
        val proxy = params?.get("proxy")?.let { it as? JsonPrimitive }?.content ?: ""

        val path = buildString {
            append("/torch/mirror-versions?mirror_key=$mirrorKey")
            if (useProxy && proxy.isNotBlank()) append("&proxy=$proxy")
        }
        logger.debug("[GetTorchMirrorVersionsJsMessageHandler] mirrorKey=$mirrorKey useProxy=$useProxy path=$path")

        val result = try {
            FredicaApi.PyUtil.get(path)
        } catch (e: Throwable) {
            logger.warn("[GetTorchMirrorVersionsJsMessageHandler] Python call failed", isHappensFrequently = false, err = e)
            buildValidJson { kv("error", e.message ?: "unknown error") }.str
        }
        callback(result)
    }
}
