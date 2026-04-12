package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.asr.model.AsrConfigSaveParam
import com.github.project_fredica.asr.service.AsrConfigService
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator

/**
 * 通过 kmpJsBridge 保存 ASR 配置（部分更新）。
 *
 * JS 调用方式：
 * ```js
 * kmpJsBridge.callNative('save_asr_config', JSON.stringify({
 *     allow_download: false,
 *     disallowed_models: "tiny,base,small"
 * }), (result) => {
 *     const updated = JSON.parse(result);
 * });
 * ```
 */
class SaveAsrConfigJsMessageHandler : MyJsMessageHandler() {
    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit
    ) {
        logger.debug("[SaveAsrConfigJsMessageHandler] params: ${message.params}")
        val param = message.params.loadJsonModel<AsrConfigSaveParam>().getOrThrow()
        AsrConfigService.saveAsrConfig(param)
        val updated = AsrConfigService.getAsrConfig()
        callback(AppUtil.dumpJsonStr(updated).getOrThrow().str)
    }
}
