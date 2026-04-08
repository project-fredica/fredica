package com.github.project_fredica.api.routes

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.error
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.llm.LlmModelConfig
import com.github.project_fredica.llm.LlmMessagesJson
import com.github.project_fredica.llm.LlmProviderException
import com.github.project_fredica.llm.LlmRequest
import com.github.project_fredica.llm.LlmRequestServiceHolder
import com.github.project_fredica.apputil.AppUtil
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.RoutingContext
import io.ktor.utils.io.writeStringUtf8
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * LLM 代理聊天路由（SSE 流式转发）。
 *
 * 前端只需传 app_model_id + messages_json，后端查找对应模型的 api_key / base_url，
 * 避免敏感信息暴露给前端或网络中间人。
 *
 * 路由：POST /api/v1/LlmProxyChatRoute（需鉴权）
 * 请求体：[LlmProxyChatRequest]
 * 响应：text/event-stream，含 data: 流式内容 + event: llm_source 元数据
 */
object LlmProxyChatRoute {
    private val logger = createLogger()

    @Serializable
    data class LlmProxyChatRequest(
        @SerialName("app_model_id")          val appModelId: String,
        /** 原始 messages JSON 字符串（支持任意 OpenAI-compatible 格式） */
        @SerialName("messages_json")         val messagesJson: String,
        @SerialName("disable_cache")         val disableCache: Boolean = false,
        /** disableCache=true 时是否覆盖旧缓存，默认 true */
        @SerialName("overwrite_on_disable")  val overwriteOnDisable: Boolean = true,
        /** 其他请求字段（temperature、max_tokens、response_format 等）JSON 字符串，可选 */
        @SerialName("extra_fields_json")     val extraFieldsJson: String? = null,
    )

    suspend fun handle(ctx: RoutingContext) {
        val call = ctx.call
        val req = call.receiveText().loadJsonModel<LlmProxyChatRequest>().getOrElse { e ->
            logger.error("LlmProxyChatRoute: 请求体解析失败", e)
            call.response.status(HttpStatusCode.BadRequest)
            return
        }

        logger.debug("[LlmProxyChatRoute] appModelId=${req.appModelId} disableCache=${req.disableCache}")

        val modelConfig = run {
            val config = AppConfigService.repo.getConfig()
            val models = config.llmModelsJson.loadJsonModel<List<LlmModelConfig>>().getOrElse { emptyList() }
            models.find { it.appModelId == req.appModelId }
        } ?: run {
            logger.warn("[LlmProxyChatRoute] 未找到模型 appModelId=${req.appModelId}")
            call.respondBytesWriter(ContentType.Text.EventStream) {
                writeStringUtf8(
                    "event: llm_error\n" +
                    "data: ${buildJsonObject { put("error_type", "MODEL_NOT_FOUND"); put("message", "未找到模型配置") }}\n\n"
                )
                flush()
            }
            return
        }

        val llmReq = LlmRequest(
            modelConfig = modelConfig,
            messages = LlmMessagesJson(req.messagesJson),
            extraFields = req.extraFieldsJson?.let {
                AppUtil.GlobalVars.json.parseToJsonElement(it).jsonObject
            },
            disableCache = req.disableCache,
            overwriteOnDisable = req.overwriteOnDisable,
        )

        try {
            call.respondBytesWriter(ContentType.Text.EventStream) {
                try {
                    val resp = LlmRequestServiceHolder.instance.streamRequest(
                        req = llmReq,
                        onChunk = { chunk ->
                            val chunkJson = buildJsonObject {
                                put("choices", buildJsonArray {
                                    add(buildJsonObject {
                                        put("delta", buildJsonObject {
                                            put("content", JsonPrimitive(chunk))
                                        })
                                    })
                                })
                            }
                            writeStringUtf8("data: ${AppUtil.GlobalVars.json.encodeToString(chunkJson)}\n\n")
                            flush()
                        },
                    )
                    // 尾部 source 元数据事件，供前端展示缓存状态
                    writeStringUtf8(
                        "event: llm_source\n" +
                        "data: ${buildJsonObject { put("source", resp.source.name); put("key_hash", resp.keyHash) }}\n\n"
                    )
                    writeStringUtf8("data: [DONE]\n\n")
                    flush()
                } catch (e: LlmProviderException) {
                    logger.warn("LlmProxyChatRoute: provider error type=${e.type} status=${e.httpStatus}", isHappensFrequently = false, err = e)
                    writeStringUtf8(
                        "event: llm_error\n" +
                        "data: ${buildJsonObject { put("error_type", e.type.name); put("message", e.providerMessage) }}\n\n"
                    )
                    flush()
                }
            }
        } catch (e: Exception) {
            val isClientDisconnect = e.message?.contains("Cannot write to channel") == true ||
                e.message?.contains("ClosedWriteChannelException") == true
            if (isClientDisconnect) {
                logger.debug("LlmProxyChatRoute: client disconnected")
            } else {
                logger.error("LlmProxyChatRoute: 异常", e)
            }
            runCatching { call.response.status(HttpStatusCode.BadGateway) }
        }
    }
}
