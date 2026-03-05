package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.json
import com.github.project_fredica.db.AppConfigService
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.json.jsonObject

/** 平台实现：调用 Python /device/detect，返回响应 JSON 文本。 */
expect suspend fun RunFfmpegDetectJsMessageHandler.deviceDetect(): String

/**
 * 通过 kmpJsBridge 触发 Python 设备检测，更新 AppConfig 并回调最新结果。
 *
 * JS 调用方式：
 * ```js
 * kmpJsBridge.callNative('run_ffmpeg_detect', '{}', (result) => {
 *     const info = JSON.parse(result);
 *     // info.device_info_json — 最新设备 GPU 能力
 *     // info.ffmpeg_probe_json — 最新 FFmpeg 探测结果
 *     // info.error — 若出错则包含错误信息
 * });
 * ```
 */
class RunFfmpegDetectJsMessageHandler : MyJsMessageHandler() {
    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit
    ) {
        try {
            val resultText = deviceDetect()
            val parsed = AppUtil.GlobalVars.json.parseToJsonElement(resultText).jsonObject
            val deviceInfoJson = parsed["device_info_json"]?.toString() ?: ""
            val ffmpegProbeJson = parsed["ffmpeg_probe_json"]?.toString() ?: ""

            val config = AppConfigService.repo.getConfig()
            AppConfigService.repo.updateConfig(
                config.copy(
                    deviceInfoJson = deviceInfoJson,
                    ffmpegProbeJson = ffmpegProbeJson,
                )
            )

            val result = buildValidJson {
                kv("device_info_json", deviceInfoJson)
                kv("ffmpeg_probe_json", ffmpegProbeJson)
            }
            callback(result.str)
        } catch (e: Throwable) {
            logger.error("RunFfmpegDetectJsMessageHandler failed: ${e.message}")
            val result = buildValidJson { kv("error", e.message ?: "unknown error") }
            callback(result.str)
        }
    }
}
