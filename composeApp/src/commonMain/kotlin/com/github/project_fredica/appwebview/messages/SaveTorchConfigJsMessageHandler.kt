package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.db.AppConfigService
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 通过 kmpJsBridge 保存用户选择的 torch variant 配置到 AppConfig。
 *
 * JS 调用方式：
 * ```js
 * kmpJsBridge.callNative('save_torch_config',
 *     JSON.stringify({
 *         torch_variant: "cu124",
 *         torch_download_use_proxy: false,
 *         torch_download_proxy_url: "",
 *         torch_download_index_url: "",
 *         torch_custom_packages: "",
 *         torch_custom_index_url: "",
 *         torch_custom_variant_id: "",
 *     }),
 *     (result) => {
 *         const r = JSON.parse(result);
 *         // r.ok    — 保存成功
 *         // r.error — 错误码
 *     }
 * );
 * ```
 */
class SaveTorchConfigJsMessageHandler : MyJsMessageHandler() {

    @Serializable
    private data class Param(
        @SerialName("torch_variant") val torchVariant: String = "",
        @SerialName("torch_download_use_proxy") val torchDownloadUseProxy: Boolean = false,
        @SerialName("torch_download_proxy_url") val torchDownloadProxyUrl: String = "",
        @SerialName("torch_download_index_url") val torchDownloadIndexUrl: String = "",
        @SerialName("torch_custom_packages") val torchCustomPackages: String = "",
        @SerialName("torch_custom_index_url") val torchCustomIndexUrl: String = "",
        @SerialName("torch_custom_variant_id") val torchCustomVariantId: String = "",
    )

    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        val param = runCatching { Json.decodeFromString<Param>(message.params) }.getOrDefault(Param())
        if (param.torchVariant.isBlank()) {
            callback(buildValidJson { kv("error", "MISSING_TORCH_VARIANT") }.str)
            return
        }

        val cfg = AppConfigService.repo.getConfig()
        AppConfigService.repo.updateConfig(cfg.copy(
            torchVariant          = param.torchVariant,
            torchDownloadUseProxy = param.torchDownloadUseProxy,
            torchDownloadProxyUrl = param.torchDownloadProxyUrl,
            torchDownloadIndexUrl = param.torchDownloadIndexUrl,
            torchCustomPackages   = param.torchCustomPackages,
            torchCustomIndexUrl   = param.torchCustomIndexUrl,
            torchCustomVariantId  = param.torchCustomVariantId,
        ))

        callback(buildValidJson { kv("ok", true) }.str)
    }
}
