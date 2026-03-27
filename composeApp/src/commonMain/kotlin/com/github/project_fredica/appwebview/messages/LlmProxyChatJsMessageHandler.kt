package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.error
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toJsonArray
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.llm.LlmCapability
import com.github.project_fredica.llm.LlmModelConfig
import com.github.project_fredica.llm.LlmSseClient
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * JsBridge：通过 Kotlin 后端（LlmSseClient）代理 LLM 聊天请求。
 *
 * 前端只需传 app_model_id + message，后端查找对应模型的 api_key / base_url，
 * 避免敏感信息（token、模型名）暴露给前端。
 *
 * 前端调用：kmpJsBridge.callNative("llm_proxy_chat", paramJson, callback)
 *
 * 参数（JSON）：
 * - app_model_id: String  — 模型的应用内标识符
 * - message: String       — 用户消息内容
 *
 * 回调：{"content": "..."} 或 {"error": "..."}
 */
class LlmProxyChatJsMessageHandler : MyJsMessageHandler() {
    override val logger = createLogger()

    @Serializable
    data class Param(
        @SerialName("app_model_id") val appModelId: String,
        val message: String,
    )

    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        val param = message.params.loadJsonModel<Param>().getOrElse { e ->
            logger.error("LlmProxyChatJsMessageHandler: 参数解析失败", e)
            callback(buildValidJson { kv("error", "参数解析失败: ${e.message}") }.str)
            return
        }

        // 从 AppConfig 中按 appModelId 查找模型配置
        val config = AppConfigService.repo.getConfig()
        val models = config.llmModelsJson.loadJsonModel<List<LlmModelConfig>>().getOrElse { emptyList() }
        val modelConfig = models.find { it.appModelId == param.appModelId }
        if (modelConfig == null) {
            logger.error("LlmProxyChatJsMessageHandler: 未找到模型 appModelId=${param.appModelId}")
            callback(buildValidJson { kv("error", "未找到模型 app_model_id='${param.appModelId}'") }.str)
            return
        }

        // 用 createJson DSL 构建请求体，避免字符串拼接导致的 JSON 注入风险
        val requestBody = createJson {
            obj {
                kv("model", modelConfig.model)
                kv("messages", listOf(
                    createJson { obj { kv("role", "user"); kv("content", param.message) } }
                ).toJsonArray())
                kv("stream", LlmCapability.STREAMING in modelConfig.capabilities)
            }
        }.toString()

        try {
            val result = LlmSseClient.streamChat(modelConfig, requestBody)
            if (result == null) {
                callback(buildValidJson { kv("error", "请求被取消") }.str)
            } else {
                callback(buildValidJson { kv("content", result) }.str)
            }
        } catch (e: Exception) {
            logger.error("LlmProxyChatJsMessageHandler: 请求失败 model=${modelConfig.model}", e)
            callback(buildValidJson { kv("error", e.message) }.str)
        }
    }
}
