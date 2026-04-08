package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.createLogger
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.db.AppConfigService
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * JsBridge：检测 B 站账号登录态是否仍然有效。
 *
 * 凭据检测涉及用户敏感信息（sessdata 等），不通过 HTTP API 暴露，
 * 仅允许由原生宿主应用（桌面端 WebView）发起。
 *
 * JS 调用方式：
 * ```js
 * // 可传入当前表单的凭据字段；若为空则自动回退到服务端 AppConfig 已保存的值
 * kmpJsBridge.callNative('check_bilibili_credential', JSON.stringify({
 *     sessdata: "...",      // 可选，为空时用 AppConfig
 *     bili_jct: "...",
 *     buvid3: "...",
 *     buvid4: "...",
 *     dedeuserid: "...",
 *     ac_time_value: "...",
 *     proxy: "...",
 * }), (result) => {
 *     const r = JSON.parse(result);
 *     // r.configured: bool   — sessdata 是否已配置
 *     // r.valid:      bool   — 账号登录态是否有效
 *     // r.message:    string — 结果描述
 * });
 * ```
 */
class CheckBilibiliCredentialJsMessageHandler : MyJsMessageHandler() {
    override val logger = createLogger()

    @Serializable
    private data class CheckParams(
        @SerialName("sessdata") val sessdata: String? = null,
        @SerialName("bili_jct") val biliJct: String? = null,
        @SerialName("buvid3") val buvid3: String? = null,
        @SerialName("buvid4") val buvid4: String? = null,
        @SerialName("dedeuserid") val dedeuserid: String? = null,
        @SerialName("ac_time_value") val acTimeValue: String? = null,
        @SerialName("proxy") val proxy: String? = null,
    )

    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        val params = message.params.loadJsonModel<CheckParams>().getOrElse { CheckParams() }
        val cfg = AppConfigService.repo.getConfig()

        // 优先使用调用方传入的凭据（表单当前值）；字段为空则回退到 AppConfig 已保存的值
        val sessdata = params.sessdata?.takeIf { it.isNotBlank() } ?: cfg.bilibiliSessdata
        val biliJct = params.biliJct?.takeIf { it.isNotBlank() } ?: cfg.bilibiliBiliJct
        val buvid3 = params.buvid3?.takeIf { it.isNotBlank() } ?: cfg.bilibiliBuvid3
        val buvid4 = params.buvid4?.takeIf { it.isNotBlank() } ?: cfg.bilibiliBuvid4
        val dedeuserid = params.dedeuserid?.takeIf { it.isNotBlank() } ?: cfg.bilibiliDedeuserid
        val acTimeValue = params.acTimeValue?.takeIf { it.isNotBlank() } ?: cfg.bilibiliAcTimeValue
        val proxy = params.proxy?.takeIf { it.isNotBlank() } ?: cfg.bilibiliProxy

        // sessdata 为空视为未配置，无需调用 Python
        if (sessdata.isBlank()) {
            logger.debug("CheckBilibiliCredential: 未配置 bilibiliSessdata，跳过检测")
            callback(buildJsonObject {
                put("configured", false)
                put("valid", false)
                put("message", "未配置账号")
            }.toString())
            return
        }

        logger.debug("CheckBilibiliCredential: 开始调用 Python credential/check")
        val pyBody = buildJsonObject {
            put("sessdata", sessdata)
            put("bili_jct", biliJct)
            put("buvid3", buvid3)
            put("buvid4", buvid4)
            put("dedeuserid", dedeuserid)
            put("ac_time_value", acTimeValue)
            put("proxy", proxy)
        }

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
