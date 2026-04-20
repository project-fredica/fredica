package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.bilibili_account_pool.model.BilibiliAccount
import com.github.project_fredica.bilibili_account_pool.service.BilibiliAccountPoolService
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GetBilibiliAccountPoolJsMessageHandler : MyJsMessageHandler() {
    override val logger = createLogger()

    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        try {
            val accounts = BilibiliAccountPoolService.repo.getAll()
            logger.debug("GetBilibiliAccountPool: count=${accounts.size}")
            callback(AppUtil.GlobalVars.json.encodeToString(
                ListSerializer(BilibiliAccount.serializer()),
                accounts,
            ))
        } catch (e: Throwable) {
            logger.warn("GetBilibiliAccountPool: 读取失败", isHappensFrequently = false, err = e)
            callback(buildJsonObject { put("error", e.message ?: "unknown") }.toString())
        }
    }
}
