package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.python.TorchService
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator

/**
 * 通过 kmpJsBridge 创建 DOWNLOAD_TORCH 任务。
 *
 * JS 调用方式：
 * ```js
 * kmpJsBridge.callNative('download_torch', JSON.stringify({
 *     variant: 'cu124',
 *     torch_version: '2.7.0',
 *     index_url: 'https://download.pytorch.org/whl/cu124',
 *     index_url_mode: 'replace',
 *     use_proxy: false,
 *     proxy: '',
 * }), (result) => {
 *     const r = JSON.parse(result);
 *     // r.task_id          — 任务 ID
 *     // r.workflow_run_id  — 工作流 ID
 *     // r.error            — 错误码（TASK_ALREADY_ACTIVE）
 * });
 * ```
 */
class DownloadTorchJsMessageHandler : MyJsMessageHandler() {
    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        val param = message.params.loadJsonModel<TorchService.DownloadParam>().getOrElse { TorchService.DownloadParam() }
        callback(TorchService.startDownload(param))
    }
}
