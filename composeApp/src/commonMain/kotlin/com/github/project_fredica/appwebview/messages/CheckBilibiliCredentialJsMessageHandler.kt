package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
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
        if (sessdata.isNullOrBlank()) {
            logger.debug("CheckBilibiliCredential: 未配置 bilibiliSessdata，跳过检测")
            callback(buildValidJson {
                kv("configured", false)
                kv("valid", false)
                kv("message", "未配置账号")
            }.str)
            return
        }

        logger.debug("CheckBilibiliCredential: 开始调用 Python credential/check")
        val pyBody = buildValidJson {
            kv("sessdata", sessdata)
            kv("bili_jct", biliJct)
            kv("buvid3", buvid3)
            kv("buvid4", buvid4)
            kv("dedeuserid", dedeuserid)
            kv("ac_time_value", acTimeValue)
            kv("proxy", proxy)
        }

        try {
            val raw = FredicaApi.PyUtil.post("/bilibili/credential/check", pyBody.str)
            logger.info("CheckBilibiliCredential: 完成，raw=$raw")
            callback(raw)
        } catch (e: Throwable) {
            logger.warn("CheckBilibiliCredential: Python 服务异常", isHappensFrequently = false, err = e)
            callback(buildValidJson {
                kv("configured", true)
                kv("valid", false)
                kv("message", "检测失败: ${e.message}")
            }.str)
        }
    }
}
