package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.json
import com.github.project_fredica.db.AppConfig
import com.github.project_fredica.db.AppConfigService
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 通过 kmpJsBridge 保存桌面服务器配置。
 *
 * 支持局部更新：params 中只需包含要修改的字段，不会覆盖其他字段。
 *
 * JS 调用方式：
 * ```js
 * kmpJsBridge.callNative('save_app_config', JSON.stringify(values), (result) => {
 *     const updated = JSON.parse(result);
 * });
 * ```
 */
class SaveAppConfigJsMessageHandler : MyJsMessageHandler() {
    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit
    ) {
        val patch = AppUtil.GlobalVars.json.parseToJsonElement(message.params).jsonObject
            .mapValues { (_, v) -> v.jsonPrimitive.content }
        AppConfigService.repo.updateConfigPartial(patch)
        val updated = AppConfigService.repo.getConfig()
        callback(AppUtil.GlobalVars.json.encodeToString<AppConfig>(updated))
    }
}
