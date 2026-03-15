package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.db.AppConfigService
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator

/**
 * 通过 kmpJsBridge 获取 torch 配置与推荐信息。
 *
 * JS 调用方式：
 * ```js
 * kmpJsBridge.callNative('get_torch_info', '{}', (result) => {
 *     const r = JSON.parse(result);
 *     // r.torch_variant              — 当前已选/已安装的 variant
 *     // r.torch_recommended_variant  — 启动时探测到的推荐 variant
 *     // r.torch_recommendation_json  — 完整推荐 JSON（含 GPU 信息）
 *     // r.torch_download_use_proxy   — 是否使用代理下载
 *     // r.torch_download_proxy_url   — 代理地址
 *     // r.torch_custom_packages      — 自定义包列表
 *     // r.torch_custom_index_url     — 自定义 index URL
 *     // r.torch_custom_variant_id    — 自定义 variant ID
 * });
 * ```
 */
class GetTorchInfoJsMessageHandler : MyJsMessageHandler() {
    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        val cfg = AppConfigService.repo.getConfig()
        callback(buildValidJson {
            kv("torch_variant", cfg.torchVariant)
            kv("torch_recommended_variant", cfg.torchRecommendedVariant)
            kv("torch_recommendation_json", cfg.torchRecommendationJson)
            kv("torch_download_use_proxy", cfg.torchDownloadUseProxy)
            kv("torch_download_proxy_url", cfg.torchDownloadProxyUrl)
            kv("torch_download_index_url", cfg.torchDownloadIndexUrl)
            kv("torch_custom_packages", cfg.torchCustomPackages)
            kv("torch_custom_index_url", cfg.torchCustomIndexUrl)
            kv("torch_custom_variant_id", cfg.torchCustomVariantId)
        }.str)
    }
}
