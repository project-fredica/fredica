package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.get
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import com.github.project_fredica.apputil.json

/**
 * 通过 kmpJsBridge 检查各 torch variant 的下载状态。
 *
 * JS 调用方式：
 * ```js
 * kmpJsBridge.callNative('get_torch_check', '{}', (result) => {
 *     const r = JSON.parse(result);
 *     // r.items — [{ variant, downloaded, version }]
 * });
 * ```
 */
class GetTorchCheckJsMessageHandler : MyJsMessageHandler() {
    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        val downloadDir = AppUtil.Paths.torchDownloadDir.absolutePath
        val result = try {
            val raw = FredicaApi.PyUtil.get("/torch/check?download_dir=$downloadDir")
            // Python 返回 { "cu124": { "already_ok": true, "installed_version": "2.3.0" }, ... }
            // 转换为前端期望的 items 数组格式
            val obj = AppUtil.GlobalVars.json.parseToJsonElement(raw) as? JsonObject
                ?: return callback(buildValidJson { kv("items", ValidJsonString("[]")) }.str)
            val items = buildString {
                append("[")
                obj.entries.forEachIndexed { i, (variant, v) ->
                    val vObj = v as? JsonObject
                    val alreadyOk = (vObj?.get("already_ok") as? JsonPrimitive)?.booleanOrNull ?: false
                    val version = (vObj?.get("installed_version") as? JsonPrimitive)?.contentOrNull
                    if (i > 0) append(",")
                    append("{\"variant\":\"$variant\",\"downloaded\":$alreadyOk,\"version\":${if (version != null) "\"$version\"" else "null"}}")
                }
                append("]")
            }
            buildValidJson { kv("items", ValidJsonString(items)) }.str
        } catch (e: Throwable) {
            logger.warn("[GetTorchCheckJsMessageHandler] failed", isHappensFrequently = false, err = e)
            buildValidJson { kv("items", ValidJsonString("[]")) }.str
        }
        callback(result)
    }
}
