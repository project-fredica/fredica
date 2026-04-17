package com.github.project_fredica.api.routes

// =============================================================================
// PromptScriptGenerateRoute (jvmMain)
// =============================================================================
//
// POST /api/v1/PromptScriptGenerateRoute
//
// 执行 Prompt 脚本 → 获取 prompt(s) → 逐段调用 LLM → SSE 流式返回。
// 脚本返回 string 时为单段模式；返回 string[] 时为分段模式（MapReduce）。
//
// 请求体：PromptScriptGenerateRequest { script_code, app_model_id, disable_cache? }
// 响应：text/event-stream
//   事件类型：
//     { "type": "script_log", "level": "...", "args": "...", "ts": 123 }
//     { "type": "script_error", "error": "...", "error_type": "..." }
//     { "type": "segment_start", "index": 0, "total": 3 }
//     { "choices": [{ "delta": { "content": "..." } }] }          ← LLM chunk
//     { "type": "segment_end", "index": 0 }
//     event: llm_source\ndata: { "source": "...", "key_hash": "..." }
//     data: [DONE]
// =============================================================================

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.error
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.llm.LlmMessagesJson
import com.github.project_fredica.llm.LlmModelConfig
import com.github.project_fredica.llm.LlmProviderException
import com.github.project_fredica.llm.LlmRequest
import com.github.project_fredica.llm.LlmRequestServiceHolder
import com.github.project_fredica.llm.LlmResponse
import com.github.project_fredica.prompt.PromptScriptRuntime
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.RoutingContext
import io.ktor.utils.io.writeStringUtf8
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class PromptScriptGenerateRequest(
    @SerialName("script_code") val scriptCode: String,
    @SerialName("app_model_id") val appModelId: String,
    @SerialName("disable_cache") val disableCache: Boolean = false,
)

object PromptScriptGenerateRoute : SseRoute {
    override val desc = "Prompt 脚本执行 + LLM 分段生成（SSE 流式）"
    override val minRole = AuthRole.TENANT

    private val logger = createLogger { "PromptScriptGenerateRoute" }

    override suspend fun handle(ctx: RoutingContext) {
        val call = ctx.call
        val req = call.receiveText().loadJsonModel<PromptScriptGenerateRequest>().getOrElse { e ->
            logger.warn("[PromptScriptGenerateRoute] 请求体解析失败", isHappensFrequently = false, err = e)
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "请求体解析失败"))
            return
        }
        if (req.scriptCode.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "script_code 不能为空"))
            return
        }
        if (req.appModelId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "app_model_id 不能为空"))
            return
        }

        // 查找模型配置
        val modelConfig = run {
            val config = AppConfigService.repo.getConfig()
            val models = config.llmModelsJson.loadJsonModel<List<LlmModelConfig>>().getOrElse { emptyList() }
            models.find { it.appModelId == req.appModelId }
        }
        if (modelConfig == null) {
            logger.warn("[PromptScriptGenerateRoute] 未找到模型 appModelId=${req.appModelId}")
            call.respondBytesWriter(ContentType.Text.EventStream) {
                writeStringUtf8(
                    "event: llm_error\n" +
                    "data: ${buildJsonObject { put("error_type", "MODEL_NOT_FOUND"); put("message", "未找到模型配置") }}\n\n"
                )
                flush()
            }
            return
        }

        logger.debug("[PromptScriptGenerateRoute] 执行请求: appModelId=${req.appModelId} scriptLength=${req.scriptCode.length}")

        // 执行脚本（使用 Run 模式信号量）
        val acquired = PromptScriptRuntime.runSemaphore.tryAcquire()
        if (!acquired) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "sandbox_busy"))
            return
        }
        val result = try {
            PromptScriptRuntime.execute(req.scriptCode, PromptScriptRuntime.Mode.RUN)
        } finally {
            PromptScriptRuntime.runSemaphore.release()
        }

        // 确定 prompt 列表
        val prompts: List<String> = result.promptTexts
            ?: listOfNotNull(result.promptText?.takeIf { it.isNotBlank() })

        // SSE 流式响应
        try {
            call.respondBytesWriter(ContentType.Text.EventStream) {
                // 1. 发送脚本日志
                for (log in result.logs) {
                    val event = buildJsonObject {
                        put("type", "script_log")
                        put("level", log.level)
                        put("args", log.args)
                        put("ts", log.ts)
                    }
                    writeStringUtf8("data: ${event}\n\n")
                    flush()
                }

                // 2. 脚本执行失败 → 发送错误并终止
                if (result.error != null) {
                    val event = buildJsonObject {
                        put("type", "script_error")
                        put("error", result.error)
                        put("error_type", result.errorType ?: "unknown")
                    }
                    writeStringUtf8("data: ${event}\n\n")
                    flush()
                    return@respondBytesWriter
                }

                // 3. 脚本成功但无 prompt → 发送空结果
                if (prompts.isEmpty()) {
                    val event = buildJsonObject {
                        put("type", "script_error")
                        put("error", "脚本未返回有效 prompt")
                        put("error_type", "empty_result")
                    }
                    writeStringUtf8("data: ${event}\n\n")
                    flush()
                    return@respondBytesWriter
                }

                // 4. 逐段调用 LLM
                val isMultiSegment = prompts.size > 1
                var lastResp: LlmResponse? = null

                for ((index, prompt) in prompts.withIndex()) {
                    if (isMultiSegment) {
                        logger.info("[PromptScriptGenerateRoute] 已循环 ${index + 1}/${prompts.size} 段")
                        val segStart = buildJsonObject {
                            put("type", "segment_start")
                            put("index", index)
                            put("total", prompts.size)
                        }
                        writeStringUtf8("data: ${segStart}\n\n")
                        flush()
                    }

                    val messagesJson = buildJsonArray {
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", JsonPrimitive(prompt))
                        })
                    }
                    val llmReq = LlmRequest(
                        modelConfig = modelConfig,
                        messages = LlmMessagesJson(AppUtil.GlobalVars.json.encodeToString(messagesJson)),
                        disableCache = req.disableCache,
                    )

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
                        lastResp = resp
                    } catch (e: LlmProviderException) {
                        logger.warn(
                            "[PromptScriptGenerateRoute] LLM provider error at segment $index: type=${e.type} status=${e.httpStatus}",
                            isHappensFrequently = false, err = e,
                        )
                        writeStringUtf8(
                            "event: llm_error\n" +
                            "data: ${buildJsonObject { put("error_type", e.type.name); put("message", e.providerMessage) }}\n\n"
                        )
                        flush()
                        return@respondBytesWriter
                    }

                    if (isMultiSegment) {
                        val segEnd = buildJsonObject {
                            put("type", "segment_end")
                            put("index", index)
                        }
                        writeStringUtf8("data: ${segEnd}\n\n")
                        flush()
                    }
                }

                // 5. 尾部 source 元数据（使用最后一段的 resp）
                if (lastResp != null) {
                    writeStringUtf8(
                        "event: llm_source\n" +
                        "data: ${buildJsonObject { put("source", lastResp.source.name); put("key_hash", lastResp.keyHash) }}\n\n"
                    )
                }
                writeStringUtf8("data: [DONE]\n\n")
                flush()
            }
        } catch (e: Exception) {
            val isClientDisconnect = e.message?.contains("Cannot write to channel") == true ||
                e.message?.contains("ClosedWriteChannelException") == true
            if (isClientDisconnect) {
                logger.debug("[PromptScriptGenerateRoute] client disconnected")
            } else {
                logger.error("[PromptScriptGenerateRoute] 异常", e)
            }
            runCatching { call.response.status(HttpStatusCode.BadGateway) }
        }
    }
}
