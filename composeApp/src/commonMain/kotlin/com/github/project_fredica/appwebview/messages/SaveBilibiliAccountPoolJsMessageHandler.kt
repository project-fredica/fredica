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

class SaveBilibiliAccountPoolJsMessageHandler : MyJsMessageHandler() {
    override val logger = createLogger()

    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        logger.debug("SaveBilibiliAccountPool: params=${message.params.take(200)}")
        try {
            val accounts = AppUtil.GlobalVars.json.decodeFromString(
                ListSerializer(BilibiliAccount.serializer()),
                message.params,
            )
            val now = System.currentTimeMillis() / 1000L
            val withTimestamps = accounts.mapIndexed { idx, acct ->
                acct.copy(
                    sortOrder = idx,
                    createdAt = if (acct.createdAt == 0L) now else acct.createdAt,
                    updatedAt = now,
                )
            }

            BilibiliAccountPoolService.repo.deleteAll()
            BilibiliAccountPoolService.repo.upsertAll(withTimestamps)
            logger.debug("SaveBilibiliAccountPool: saved count=${withTimestamps.size}")

            callback(buildJsonObject { put("ok", true) }.toString())
        } catch (e: Throwable) {
            logger.warn("SaveBilibiliAccountPool: 保存失败", isHappensFrequently = false, err = e)
            callback(buildJsonObject { put("error", e.message ?: "unknown") }.toString())
        }
    }
}
