package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.AppConfigService
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * JsBridge：尝试刷新当前 B 站账号凭据。
 *
 * 此操作仅通过 kmpJsBridge 暴露给内嵌 WebView，不走 HTTP API，
 * 以确保凭据刷新只能由原生宿主应用发起。
 *
 * 调用方应传入当前表单的凭据字段（最新值），以便刷新未保存的编辑内容；
 * 若字段为空则回退到 AppConfig 中已保存的值。
 *
 * JS 调用方式：
 * ```js
 * kmpJsBridge.callNative('try_refresh_bilibili_credential', JSON.stringify({
 *     sessdata: "...",      // 可选，为空时用 AppConfig
 *     bili_jct: "...",
 *     buvid3: "...",
 *     buvid4: "...",
 *     dedeuserid: "...",
 *     ac_time_value: "...",
 *     proxy: "...",
 * }), (result) => {
 *     const r = JSON.parse(result);
 *     // r.success:       bool   — 操作是否成功
 *     // r.refreshed:     bool   — 是否实际执行了刷新（false 表示无需刷新）
 *     // r.message:       string — 结果描述
 *     // r.sessdata:      string? — 刷新后的新值（refreshed=true 时存在）
 *     // r.bili_jct:      string?
 *     // r.buvid3:        string?
 *     // r.buvid4:        string?
 *     // r.dedeuserid:    string?
 *     // r.ac_time_value: string?
 *     // r.error:         string? — 异常描述（Python 服务不可用等）
 * });
 * ```
 */
class TryRefreshBilibiliCredentialJsMessageHandler : MyJsMessageHandler() {
    override val logger = createLogger()

    @Serializable
    private data class RefreshParams(
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
        val params = message.params.loadJsonModel<RefreshParams>().getOrElse { RefreshParams() }
        val cfg = AppConfigService.repo.getConfig()

        // 优先使用调用方传入的凭据（表单当前值）；字段为空则回退到 AppConfig 已保存的值
        val sessdata = params.sessdata?.takeIf { it.isNotBlank() } ?: cfg.bilibiliSessdata
        val biliJct = params.biliJct?.takeIf { it.isNotBlank() } ?: cfg.bilibiliBiliJct
        val buvid3 = params.buvid3?.takeIf { it.isNotBlank() } ?: cfg.bilibiliBuvid3
        val buvid4 = params.buvid4?.takeIf { it.isNotBlank() } ?: cfg.bilibiliBuvid4
        val dedeuserid = params.dedeuserid?.takeIf { it.isNotBlank() } ?: cfg.bilibiliDedeuserid
        val acTimeValue = params.acTimeValue?.takeIf { it.isNotBlank() } ?: cfg.bilibiliAcTimeValue
        val proxy = params.proxy?.takeIf { it.isNotBlank() } ?: cfg.bilibiliProxy

        // sessdata 为空视为未配置，直接返回
        if (sessdata.isNullOrBlank()) {
            logger.debug("TryRefreshBilibiliCredential: 未配置 bilibiliSessdata，跳过")
            callback(buildValidJson {
                kv("success", false)
                kv("refreshed", false)
                kv("message", "未配置账号")
            }.str)
            return
        }

        logger.debug("TryRefreshBilibiliCredential: 开始调用 Python try-refresh")
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
            val raw = FredicaApi.PyUtil.post("/bilibili/credential/try-refresh", pyBody.str)
            logger.info("TryRefreshBilibiliCredential: 完成，raw=$raw")
            callback(raw)
        } catch (e: Throwable) {
            logger.warn("TryRefreshBilibiliCredential: Python 服务异常", isHappensFrequently = false, err = e)
            callback(buildValidJson {
                kv("success", false)
                kv("refreshed", false)
                kv("message", "刷新失败: ${e.message}")
                kv("error", e.message ?: "unknown")
            }.str)
        }
    }
}

