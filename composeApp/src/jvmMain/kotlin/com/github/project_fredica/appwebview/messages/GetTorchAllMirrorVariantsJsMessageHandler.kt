package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.get
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.db.TorchMirrorCacheService
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 并发查询所有镜像支持的 variant，合并返回去重后的列表。
 * 结果由 [TorchMirrorCacheService] 缓存（TTL 1 小时），避免每次进入页面都发起网络请求。
 *
 * JS 调用：
 * ```js
 * callBridge('get_torch_all_mirror_variants', JSON.stringify({ use_proxy: false, proxy: '' }))
 * // => { "variants": ["cu128", "cu126", ...], "per_mirror": {"nju": [...], ...} }
 * ```
 */
class GetTorchAllMirrorVariantsJsMessageHandler : MyJsMessageHandler() {
    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        val params = try {
            AppUtil.GlobalVars.json.parseToJsonElement(message.params) as? JsonObject
        } catch (e: Throwable) {
            logger.warn("[GetTorchAllMirrorVariantsJsMessageHandler] failed to parse params", isHappensFrequently = false, err = e)
            null
        }

        val useProxy = params?.get("use_proxy")?.let { it as? JsonPrimitive }?.booleanOrNull ?: false
        val proxy = params?.get("proxy")?.let { it as? JsonPrimitive }?.content ?: ""

        val path = buildString {
            append("/torch/all-mirror-variants/")
            if (useProxy && proxy.isNotBlank()) append("?proxy=$proxy")
        }
        logger.debug("[GetTorchAllMirrorVariantsJsMessageHandler] useProxy=$useProxy path=$path")

        val result = try {
            TorchMirrorCacheService.getOrFetch { FredicaApi.PyUtil.get(path) }
        } catch (e: Throwable) {
            logger.warn("[GetTorchAllMirrorVariantsJsMessageHandler] fetch failed", isHappensFrequently = false, err = e)
            buildJsonObject { put("error", e.message ?: "unknown error") }.toString()
        }
        callback(result)
    }
}
