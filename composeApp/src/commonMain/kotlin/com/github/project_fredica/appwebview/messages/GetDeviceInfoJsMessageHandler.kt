package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.loadJson
import com.github.project_fredica.db.AppConfigService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator

/**
 * 通过 kmpJsBridge 获取设备 GPU 能力与 FFmpeg 探测信息。
 *
 * JS 调用方式：
 * ```js
 * kmpJsBridge.callNative('get_device_info', '{}', (result) => {
 *     const info = JSON.parse(result);
 *     // info.device_info_json — 设备 GPU 能力（JSON 对象）
 *     // info.ffmpeg_probe_json — FFmpeg 探测结果（JSON 对象）
 *     // info.ffmpeg_path — 用户配置的 FFmpeg 路径
 *     // info.ffmpeg_hw_accel — 用户配置的加速方案
 * });
 * ```
 */
class GetDeviceInfoJsMessageHandler : MyJsMessageHandler() {
    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit
    ) {
        val config = AppConfigService.repo.getConfig()
        val result = buildJsonObject {
            put("device_info_json", config.deviceInfoJson)
            put("ffmpeg_probe_json", config.ffmpegProbeJson)
            put("ffmpeg_path", config.ffmpegPath)
            put("ffmpeg_hw_accel", config.ffmpegHwAccel)
            put("ffmpeg_auto_detect", config.ffmpegAutoDetect)
        }
        logger.debug("result.str is ${result}")
        callback(result.toString())
    }
}
