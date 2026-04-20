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

/**
 * JsBridge：尝试刷新当前 B 站账号凭据。
 *
 * 前端传入 account_id，本 handler 从 DB 读取账号信息并解析代理后转发给 Python 刷新。
 */
class TryRefreshBilibiliCredentialJsMessageHandler : MyJsMessageHandler() {
    override val logger = createLogger()

    @Serializable
    private data class RefreshParams(
        @SerialName("account_id") val accountId: String,
    )

    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        val params = message.params.loadJsonModel<RefreshParams>().getOrElse {
            return callback(buildJsonObject { put("error", "参数解析失败") }.toString())
        }

        val account = BilibiliAccountPoolService.repo.getById(params.accountId)
        if (account == null) {
            return callback(buildJsonObject { put("error", "请先保存账号配置") }.toString())
        }

        if (account.isAnonymous || account.bilibiliSessdata.isBlank()) {
            callback(buildJsonObject {
                put("success", false)
                put("refreshed", false)
                put("message", "未配置账号")
            }.toString())
            return
        }

        logger.debug("TryRefreshBilibiliCredential: accountId=${params.accountId}")
        val pyBody = BilibiliAccountPoolService.buildPyCredentialBody(account)

        try {
            val raw = FredicaApi.PyUtil.post("/bilibili/credential/try-refresh", pyBody.toString())
            logger.info("TryRefreshBilibiliCredential: 完成，raw=$raw")
            callback(raw)
        } catch (e: Throwable) {
            logger.warn("TryRefreshBilibiliCredential: Python 服务异常", isHappensFrequently = false, err = e)
            callback(buildJsonObject {
                put("success", false)
                put("refreshed", false)
                put("message", "刷新失败: ${e.message}")
                put("error", e.message ?: "unknown")
            }.toString())
        }
    }
}
