package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.bilibili_account_pool.service.BilibiliAccountPoolService
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CheckBilibiliIpJsMessageHandler : MyJsMessageHandler() {
    override val logger = createLogger()

    @Serializable
    private data class IpCheckParams(
        @SerialName("account_id") val accountId: String,
    )

    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        val params = message.params.loadJsonModel<IpCheckParams>().getOrElse {
            return callback(buildJsonObject { put("error", "参数解析失败") }.toString())
        }

        val account = BilibiliAccountPoolService.repo.getById(params.accountId)
        if (account == null) {
            return callback(buildJsonObject { put("error", "请先保存账号配置") }.toString())
        }

        val resolvedProxy = BilibiliAccountPoolService.resolveProxy(account)
        logger.debug("CheckBilibiliIp: accountId=${params.accountId} proxy=${resolvedProxy.take(30)}")

        val pyBody = buildJsonObject {
            put("proxy", resolvedProxy)
            put("impersonate", account.bilibiliImpersonate)
        }

        try {
            val raw = FredicaApi.PyUtil.post("/bilibili/ip/check", pyBody.toString())
            callback(raw)
        } catch (e: Throwable) {
            logger.warn("CheckBilibiliIp: Python 服务异常", isHappensFrequently = false, err = e)
            callback(buildJsonObject { put("error", "IP 检测失败: ${e.message}") }.toString())
        }
    }
}
