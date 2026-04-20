package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.bilibili_account_pool.model.BilibiliAccountInfo
import com.github.project_fredica.bilibili_account_pool.service.BilibiliAccountInfoService
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GetBilibiliAccountInfoJsMessageHandler : MyJsMessageHandler() {
    override val logger = createLogger()

    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        try {
            val all = BilibiliAccountInfoService.repo.getAll()
            logger.debug("GetBilibiliAccountInfo: count=${all.size}")
            callback(AppUtil.GlobalVars.json.encodeToString(
                MapSerializer(String.serializer(), BilibiliAccountInfo.serializer()),
                all,
            ))
        } catch (e: Throwable) {
            logger.warn("GetBilibiliAccountInfo: 读取失败", isHappensFrequently = false, err = e)
            callback(buildJsonObject { put("error", e.message ?: "unknown") }.toString())
        }
    }
}
