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
import kotlinx.serialization.json.contentOrNull

/**
 * 返回指定 variant 的 pip install 命令字符串，由 Python /torch/pip-command 生成。
 *
 * JS 调用：
 * ```js
 * callBridge('get_torch_pip_command', JSON.stringify({
 *     torch_version: '2.7.0',    // 可选，空串时安装最新版
 *     index_url: 'https://download.pytorch.org/whl/cu124',
 *     variant: 'cu124',          // 可选，用于构造 --target 子目录名
 *     index_url_mode: 'replace', // 可选
 *     use_proxy: false,
 *     proxy: '',
 * }))
 * // => { "command": "pip install torch==2.7.0 torchvision torchaudio --index-url ..." }
 * ```
 *
 * index_url 为空时返回 `{ "command": "" }`。
 */
class GetTorchPipCommandJsMessageHandler : MyJsMessageHandler() {
    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        val params = try {
            AppUtil.GlobalVars.json.parseToJsonElement(message.params) as? JsonObject
        } catch (e: Throwable) {
            logger.warn("[GetTorchPipCommandJsMessageHandler] failed to parse params", isHappensFrequently = false, err = e)
            null
        }

        val torchVersion = (params?.get("torch_version") as? JsonPrimitive)?.contentOrNull ?: ""
        val variant = (params?.get("variant") as? JsonPrimitive)?.contentOrNull ?: ""
        val downloadDir = AppUtil.Paths.torchDownloadDir.absolutePath
        val useProxy = (params?.get("use_proxy") as? JsonPrimitive)?.booleanOrNull ?: false
        val proxy = (params?.get("proxy") as? JsonPrimitive)?.contentOrNull ?: ""
        val indexUrl = (params?.get("index_url") as? JsonPrimitive)?.contentOrNull ?: ""
        val indexUrlMode = (params?.get("index_url_mode") as? JsonPrimitive)?.contentOrNull ?: "replace"

        if (indexUrl.isBlank()) {
            callback(buildValidJson { kv("command", "") }.str)
            return
        }

        val path = buildString {
            append("/torch/pip-command?index_url=$indexUrl")
            if (torchVersion.isNotBlank()) append("&torch_version=$torchVersion")
            append("&download_dir=$downloadDir")
            if (variant.isNotBlank()) append("&variant=$variant")
            append("&index_url_mode=$indexUrlMode")
            if (useProxy && proxy.isNotBlank()) append("&use_proxy=true&proxy=$proxy")
        }

        logger.warn("[GetTorchPipCommandJsMessageHandler] variant=$variant torchVersion=$torchVersion indexUrl=$indexUrl indexUrlMode=$indexUrlMode useProxy=$useProxy path=$path")

        val result = try {
            FredicaApi.PyUtil.get(path)
        } catch (e: Throwable) {
            logger.warn("[GetTorchPipCommandJsMessageHandler] Python call failed for variant=$variant", isHappensFrequently = false, err = e)
            buildValidJson { kv("error", e.message ?: "unknown error") }.str
        }
        callback(result)
    }
}
