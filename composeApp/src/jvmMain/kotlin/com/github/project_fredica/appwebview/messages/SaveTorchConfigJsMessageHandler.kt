package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.python.TorchService
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator

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
    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        val param = message.params.loadJsonModel<TorchService.SaveConfigParam>().getOrElse { TorchService.SaveConfigParam() }
        callback(TorchService.saveConfig(param))
    }
}
