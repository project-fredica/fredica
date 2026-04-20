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
 * JsBridge：检测 B 站账号登录态是否仍然有效。
 *
 * 前端传入 account_id，本 handler 从 DB 读取账号信息并解析代理后转发给 Python 检测。
 */
class CheckBilibiliCredentialJsMessageHandler : MyJsMessageHandler() {
    override val logger = createLogger()

    @Serializable
    private data class CheckParams(
        @SerialName("account_id") val accountId: String,
    )

    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        val params = message.params.loadJsonModel<CheckParams>().getOrElse {
            return callback(buildJsonObject { put("error", "参数解析失败") }.toString())
        }

        val account = BilibiliAccountPoolService.repo.getById(params.accountId)
        if (account == null) {
            return callback(buildJsonObject { put("error", "请先保存账号配置") }.toString())
        }

        if (account.isAnonymous || account.bilibiliSessdata.isBlank()) {
            callback(buildJsonObject {
                put("configured", false)
                put("valid", false)
                put("message", "未配置账号")
            }.toString())
            return
        }

        logger.debug("CheckBilibiliCredential: accountId=${params.accountId}")
        val pyBody = BilibiliAccountPoolService.buildPyCredentialBody(account)

        try {
            val raw = FredicaApi.PyUtil.post("/bilibili/credential/check", pyBody.toString())
            logger.info("CheckBilibiliCredential: 完成，raw=$raw")
            callback(raw)
        } catch (e: Throwable) {
            logger.warn("CheckBilibiliCredential: Python 服务异常", isHappensFrequently = false, err = e)
            callback(buildJsonObject {
                put("configured", true)
                put("valid", false)
                put("message", "检测失败: ${e.message}")
            }.toString())
        }
    }
}
