package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.auth.AuditLogEntry
import com.github.project_fredica.auth.AuditLogService
import com.github.project_fredica.auth.AuthServiceHolder
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * JsBridge：首次初始化实例（创建 root 用户）。
 *
 * 仅 WebView 环境可调用，外部 HTTP 客户端无法访问。
 */
class InstanceInitJsMessageHandler : MyJsMessageHandler() {
    override val logger = createLogger()

    @Serializable
    private data class Param(
        val username: String,
        val password: String,
    )

    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        val p = message.params.loadJsonModel<Param>().getOrElse {
            return callback(buildJsonObject { put("error", "请求参数无效") }.toString())
        }

        if (!p.username.matches(Regex("^[a-zA-Z0-9_]{3,32}$"))) {
            return callback(buildJsonObject { put("error", "用户名格式无效（3-32 字符，仅字母数字下划线）") }.toString())
        }

        if (p.password.length < 8 || p.password.length > 128 || p.password.isBlank()) {
            return callback(buildJsonObject { put("error", "密码长度需 8-128 字符且不能全为空白") }.toString())
        }

        logger.debug("InstanceInit: username=${p.username}")

        val result = AuthServiceHolder.instance.initializeInstance(
            username = p.username,
            password = p.password,
        )

        if (result.success && result.user != null) {
            val user = result.user!!
            AuditLogService.repo.insert(
                AuditLogEntry(
                    id = "",
                    timestamp = 0L,
                    eventType = "INSTANCE_INITIALIZED",
                    actorUserId = user.id,
                    actorUsername = user.username,
                    details = "instance initialized with root user: ${user.username}",
                )
            )
        }

        callback(AppUtil.dumpJsonStr(result).getOrThrow().str)
    }
}
