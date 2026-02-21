package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.json
import com.github.project_fredica.db.AppConfig
import com.github.project_fredica.db.AppConfigService
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator

/**
 * 通过 kmpJsBridge 获取桌面服务器配置。
 *
 * JS 调用方式：
 * ```js
 * kmpJsBridge.callNative('get_app_config', '{}', (result) => {
 *     const config = JSON.parse(result);
 * });
 * ```
 */
class GetAppConfigJsMessageHandler : MyJsMessageHandler() {
    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit
    ) {
        val config = AppConfigService.repo.getConfig()
        callback(AppUtil.GlobalVars.json.encodeToString<AppConfig>(config))
    }
}
