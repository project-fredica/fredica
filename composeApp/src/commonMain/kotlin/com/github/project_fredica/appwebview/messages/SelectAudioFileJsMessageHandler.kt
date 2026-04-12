package com.github.project_fredica.appwebview.messages

import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 通过 kmpJsBridge 打开系统文件选择对话框，选择音频文件。
 *
 * JS 调用方式：
 * ```js
 * kmpJsBridge.callNative('select_audio_file', '{}', (result) => {
 *     const { path } = JSON.parse(result);
 *     if (path) { /* 用户选择了文件 */ }
 * });
 * ```
 */
class SelectAudioFileJsMessageHandler : MyJsMessageHandler() {
    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit
    ) {
        val path = selectAudioFile()
        callback(buildJsonObject { put("path", path) }.toString())
    }
}

/**
 * 平台相关的音频文件选择对话框。
 * 返回选中文件的绝对路径，用户取消则返回 null。
 */
expect suspend fun selectAudioFile(): String?
