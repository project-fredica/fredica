package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.auth.AuthSessionService
import com.github.project_fredica.auth.UserService
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * JsBridge：为 WebView 环境颁发 ROOT 级别的 session token。
 *
 * WebView 是桌面应用本体，天然可信。此 handler 查找 root 用户并创建 session，
 * 返回 session_token 供前端 appConfig 存储，使 apiFetch 的 HTTP 请求携带 ROOT 身份。
 *
 * 返回：
 * ```json
 * {
 *   "session_token": "fredica_session:xxx",
 *   "user_role": "root",
 *   "user_display_name": "admin"
 * }
 * ```
 * 实例未初始化时返回 `{ "not_initialized": true }`。
 */
class GetSessionTokenJsMessageHandler : MyJsMessageHandler() {
    override val logger = createLogger()

    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        val allUsers = UserService.repo.listAll()
        val rootUser = allUsers.firstOrNull { it.role == "root" && it.status == "active" }

        if (rootUser == null) {
            callback(buildJsonObject { put("not_initialized", true) }.toString())
            return
        }

        val session = AuthSessionService.repo.createSession(
            userId = rootUser.id,
            userAgent = "WebView/Desktop",
        )

        UserService.repo.updateLastLoginAt(rootUser.id)

        val permissions = rootUser.permissions.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        callback(buildJsonObject {
            put("session_token", "fredica_session:${session.token}")
            put("user_role", "root")
            put("user_display_name", rootUser.displayName)
            put("user_permissions", permissions.joinToString(","))
        }.toString())
    }
}
