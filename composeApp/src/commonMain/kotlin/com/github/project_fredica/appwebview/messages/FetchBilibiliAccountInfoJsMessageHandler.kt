package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.bilibili_account_pool.model.BilibiliAccountInfo
import com.github.project_fredica.bilibili_account_pool.service.BilibiliAccountInfoService
import com.github.project_fredica.bilibili_account_pool.service.BilibiliAccountPoolService
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class FetchBilibiliAccountInfoJsMessageHandler : MyJsMessageHandler() {
    override val logger = createLogger()

    @Serializable
    private data class Params(
        @SerialName("account_id") val accountId: String,
    )

    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        val params = message.params.loadJsonModel<Params>().getOrElse {
            return callback(buildJsonObject { put("error", "参数解析失败") }.toString())
        }
        logger.debug("FetchBilibiliAccountInfo: accountId=${params.accountId}")
        val account = BilibiliAccountPoolService.repo.getById(params.accountId)
        if (account == null) {
            return callback(buildJsonObject { put("error", "请先保存账号配置") }.toString())
        }
        try {
            val info = BilibiliAccountInfoService.fetchAndSave(account)
            callback(AppUtil.GlobalVars.json.encodeToString(BilibiliAccountInfo.serializer(), info))
        } catch (e: Throwable) {
            logger.warn("FetchBilibiliAccountInfo: 获取失败", isHappensFrequently = false, err = e)
            callback(buildJsonObject { put("error", "获取账号信息失败: ${e.message}") }.toString())
        }
    }
}
