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
 * 返回指定 variant 的 pip install 命令字符串，由 Python /torch/pip-command 生成。
 *
 * JS 调用：
 * ```js
 * callBridge('get_torch_pip_command', JSON.stringify({ variant: 'cu124' }))
 * // => { "command": "pip install torch==2.6.0+cu124 ... --index-url ..." }
 * ```
 *
 * variant 为空或 "custom" 时返回 `{ "command": "" }`。
 */
class GetTorchPipCommandJsMessageHandler : MyJsMessageHandler() {
    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        // 解析 params 中的各字段
        val params = try {
            AppUtil.GlobalVars.json.parseToJsonElement(message.params) as? JsonObject
        } catch (e: Throwable) {
            logger.warn("[GetTorchPipCommandJsMessageHandler] failed to parse params", isHappensFrequently = false, err = e)
            null
        }

        val variant = params?.get("variant")?.let { it as? JsonPrimitive }?.content ?: ""
        if (variant.isBlank()) {
            callback(buildValidJson { kv("command", "") }.str)
            return
        }

        val downloadDir = AppUtil.Paths.torchDownloadDir.absolutePath
        val useProxy = params?.get("use_proxy")?.let { it as? JsonPrimitive }?.booleanOrNull ?: false
        val proxy = params?.get("proxy")?.let { it as? JsonPrimitive }?.content ?: ""
        // 可选：用户自定义 index_url（如国内镜像），覆盖默认官方源
        val indexUrl = params?.get("index_url")?.let { it as? JsonPrimitive }?.content ?: ""
        val indexUrlMode = params?.get("index_url_mode")?.let { it as? JsonPrimitive }?.content ?: "replace"

        logger.debug("[GetTorchPipCommandJsMessageHandler] querying pip command for variant=$variant useProxy=$useProxy indexUrl=${indexUrl.ifBlank { "(default)" }} indexUrlMode=$indexUrlMode")

        val path = buildString {
            append("/torch/pip-command?variant=$variant&download_dir=$downloadDir")
            if (useProxy && proxy.isNotBlank()) append("&use_proxy=true&proxy=$proxy")
            if (indexUrl.isNotBlank()) {
                append("&index_url=$indexUrl")
                append("&index_url_mode=$indexUrlMode")
            }
        }

        val result = try {
            FredicaApi.PyUtil.get(path)
        } catch (e: Throwable) {
            logger.warn("[GetTorchPipCommandJsMessageHandler] Python call failed for variant=$variant", isHappensFrequently = false, err = e)
            buildValidJson { kv("command", "") }.str
        }
        callback(result)
    }
}

