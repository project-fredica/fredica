package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.error
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.llm.LlmModelConfig
import com.github.project_fredica.llm.LlmMessagesJson
import com.github.project_fredica.llm.LlmProviderException
import com.github.project_fredica.llm.LlmRequest
import com.github.project_fredica.llm.LlmRequestServiceHolder
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * JsBridge：通过 LlmRequestService 代理 LLM 聊天请求（含缓存）。
 *
 * 前端只需传 app_model_id + messages_json，后端查找对应模型的 api_key / base_url，
 * 避免敏感信息（token、模型名）暴露给前端。
 *
 * 前端调用：kmpJsBridge.callNative("llm_proxy_chat", paramJson, callback)
 *
 * 参数（JSON）：
 * - app_model_id: String      — 模型的应用内标识符
 * - messages_json: String     — 原始 messages JSON 字符串
 * - disable_cache: Boolean    — 是否跳过缓存，默认 false
 * - overwrite_on_disable: Boolean — disableCache=true 时是否覆盖写缓存，默认 true
 * - extra_fields_json: String? — 其他请求字段 JSON 字符串，可选
 *
 * 回调：{"content": "...", "source": "CACHE|LLM_FRESH", "key_hash": "..."} 或 {"error": "...", "error_type": "..."}
 */
class LlmProxyChatJsMessageHandler : MyJsMessageHandler() {
    override val logger = createLogger()

    @Serializable
    data class Param(
        @SerialName("app_model_id")          val appModelId: String,
        @SerialName("messages_json")         val messagesJson: String,
        @SerialName("disable_cache")         val disableCache: Boolean = false,
        @SerialName("overwrite_on_disable")  val overwriteOnDisable: Boolean = true,
        @SerialName("extra_fields_json")     val extraFieldsJson: String? = null,
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

        logger.warn("[LlmProxyChatJsMessageHandler] appModelId=${param.appModelId} disableCache=${param.disableCache}")

        // 从 AppConfig 中按 appModelId 查找模型配置
        val config = AppConfigService.repo.getConfig()
        val models = config.llmModelsJson.loadJsonModel<List<LlmModelConfig>>().getOrElse { emptyList() }
        val modelConfig = models.find { it.appModelId == param.appModelId }
        if (modelConfig == null) {
            logger.error("LlmProxyChatJsMessageHandler: 未找到模型 appModelId=${param.appModelId}")
            callback(buildValidJson { kv("error", "未找到模型 app_model_id='${param.appModelId}'") }.str)
            return
        }

        val llmReq = LlmRequest(
            modelConfig = modelConfig,
            messages = LlmMessagesJson(param.messagesJson),
            disableCache = param.disableCache,
            overwriteOnDisable = param.overwriteOnDisable,
        )

        try {
            val resp = LlmRequestServiceHolder.instance.request(llmReq)
            callback(buildValidJson {
                kv("content", resp.text)
                kv("source", resp.source.name)
                kv("key_hash", resp.keyHash)
            }.str)
        } catch (e: LlmProviderException) {
            logger.warn("[LlmProxyChatJsMessageHandler] provider error type=${e.type}", isHappensFrequently = false, err = e)
            callback(buildValidJson { kv("error", e.providerMessage); kv("error_type", e.type.name) }.str)
        } catch (e: Exception) {
            logger.error("[LlmProxyChatJsMessageHandler] unexpected error", e)
            callback(buildValidJson { kv("error", e.message ?: "unknown error") }.str)
        }
    }
}
