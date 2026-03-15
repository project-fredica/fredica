package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.json
import com.github.project_fredica.db.AppConfigService
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 通过 kmpJsBridge 重新探测 GPU，更新 torch 推荐版本。
 *
 * JS 调用方式：
 * ```js
 * kmpJsBridge.callNative('run_torch_detect', '{}', (result) => {
 *     const r = JSON.parse(result);
 *     // r.torch_recommended_variant  — 最新推荐 variant
 *     // r.torch_recommendation_json  — 完整推荐 JSON
 *     // r.error                      — 错误信息（可选）
 * });
 * ```
 */
class RunTorchDetectJsMessageHandler : MyJsMessageHandler() {
    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        try {
            val resultText = FredicaApi.PyUtil.post(
                path = "/torch/resolve-spec",
                body = "",
                timeoutMs = 30_000L,
            )
            val torchJson = AppUtil.GlobalVars.json.parseToJsonElement(resultText).jsonObject
            val recommendedVariant = torchJson["recommended_variant"]?.jsonPrimitive?.contentOrNull ?: ""

            val cfg = AppConfigService.repo.getConfig()
            AppConfigService.repo.updateConfig(cfg.copy(
                torchRecommendedVariant = recommendedVariant,
                torchRecommendationJson = resultText,
            ))

            callback(buildValidJson {
                kv("torch_recommended_variant", recommendedVariant)
                kv("torch_recommendation_json", resultText)
            }.str)
        } catch (e: Throwable) {
            callback(buildValidJson { kv("error", e.message ?: "detect failed") }.str)
        }
    }
}
