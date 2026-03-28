package com.github.project_fredica.llm

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.SseLineParser
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.error
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.warn
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * OpenAI 兼容 SSE 流式客户端。
 *
 * 职责：
 * - 向 `{baseUrl}/chat/completions` 发起 POST 请求
 * - 逐行解析 SSE 响应，提取 `choices[0].delta.content` 片段并回调
 * - 依赖协程结构化取消：通过 isActive 检测取消信号，抛 CancellationException 向上传播
 * - 当模型不含 [LlmCapability.STREAMING] 时自动降级为普通 POST（非流式）
 * - HTTP 非 2xx 时解析错误体，抛出 [LlmProviderException]（不写缓存）
 *
 * 使用 [AppUtil.GlobalVars.ktorClientLocal]（直连，不走代理）。
 */
object LlmSseClient {
    private val logger = createLogger()

    /**
     * 流式调用 OpenAI 兼容 Chat Completions API（SSE）。
     *
     * @param modelConfig  模型配置（含 baseUrl、apiKey、capabilities）
     * @param requestBody  完整请求 JSON 字符串，调用方负责构造（含 messages 等字段；若模型不支持流式则内部会改写 stream=false）
     * @param onChunk      每收到一个 delta.content 片段时的回调，可用于实时更新 UI 或进度
     * @return 所有 delta 拼接后的完整 content 字符串
     * @throws LlmProviderException 上游 HTTP 非 2xx 时
     * @throws CancellationException 协程被取消时（必须向上传播，不可吞掉）
     */
    suspend fun streamChat(
        modelConfig: LlmModelConfig,
        requestBody: String,
        onChunk: (suspend (String) -> Unit)? = null,
    ): String = withContext(Dispatchers.IO) {
        // 模型不支持流式输出时降级为普通 POST，并强制请求体 stream=false，避免上游仍返回 SSE
        if (LlmCapability.STREAMING !in modelConfig.capabilities) {
            logger.info("streamChat: model=${modelConfig.model} 不含 STREAMING 能力，降级为非流式请求")
            return@withContext fetchNonStreaming(
                modelConfig = modelConfig,
                requestBody = normalizeRequestBodyForStreamingCapability(requestBody, supportsStreaming = false),
            )
        }

        val fullContent = StringBuilder()
        logger.debug("streamChat start: model=${modelConfig.model} url=${modelConfig.baseUrl}")

        try {
            AppUtil.GlobalVars.ktorClientProxied.preparePost("${modelConfig.baseUrl}/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer ${modelConfig.apiKey}")
                // 声明接受 SSE 事件流，部分服务端依赖此 header 决定是否开启流式模式
                header(HttpHeaders.Accept, "text/event-stream")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.execute { response ->
                logger.debug("streamChat response: status=${response.status} model=${modelConfig.model}")

                if (!response.status.value.toString().startsWith("2")) {
                    // HTTP 非 2xx：解析错误体，抛出 LlmProviderException，不写缓存
                    val errBody = response.bodyAsText()
                    logger.error("streamChat HTTP error: status=${response.status} body=$errBody")
                    throw resolveProviderException(response.status.value, errBody)
                }

                val channel = response.bodyAsChannel()
                var lineCount = 0
                var chunkCount = 0

                while (!channel.isClosedForRead) {
                    // 每行读取前检查结构化取消信号
                    if (!isActive) {
                        logger.info("streamChat cancel detected mid-stream: model=${modelConfig.model} lines=$lineCount chunks=$chunkCount")
                        return@execute
                    }

                    val line = channel.readUTF8Line() ?: break
                    lineCount++

                    // SseLineParser 过滤非 data 行（注释行、空行、event/id 行等）
                    val data = SseLineParser.parseLine(line) ?: continue

                    // "[DONE]" 是 OpenAI SSE 协议的流结束标志
                    if (data == "[DONE]") {
                        logger.debug("streamChat [DONE] received: model=${modelConfig.model} chunks=$chunkCount")
                        break
                    }

                    // 从单条 data JSON 中提取 delta.content；解析失败时跳过（部分服务端会发送非标准行）
                    val chunk = extractDeltaContent(data)
                    if (chunk == null) {
                        logger.debug("streamChat skip non-content chunk: data=$data")
                        continue
                    }

                    fullContent.append(chunk)
                    chunkCount++
                    onChunk?.invoke(chunk)
                }

                logger.debug("streamChat stream end: model=${modelConfig.model} lines=$lineCount chunks=$chunkCount totalLen=${fullContent.length}")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            logger.debug("streamChat cancelled: model=${modelConfig.model}")
            throw e  // 必须 re-throw，不可吞掉
        } catch (e: LlmProviderException) {
            throw e  // 直接向上传播，不包装
        } catch (e: Exception) {
            logger.error("streamChat exception: model=${modelConfig.model}", e)
            throw e
        }

        val result = fullContent.toString()
        logger.info("streamChat done: model=${modelConfig.model} totalLen=${result.length} content=$result")
        result
    }

    /**
     * 非流式降级：直接 POST 并读取完整 JSON 响应体中的 `choices[0].message.content`。
     *
     * 当模型配置不含 [LlmCapability.STREAMING] 时由 [streamChat] 自动调用。
     */
    private suspend fun fetchNonStreaming(
        modelConfig: LlmModelConfig,
        requestBody: String,
    ): String = withContext(Dispatchers.IO) {
        logger.debug("fetchNonStreaming start: model=${modelConfig.model} url=${modelConfig.baseUrl}")
        try {
            val response = AppUtil.GlobalVars.ktorClientLocal.post("${modelConfig.baseUrl}/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer ${modelConfig.apiKey}")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
            logger.debug("fetchNonStreaming response: status=${response.status} model=${modelConfig.model}")

            if (!response.status.value.toString().startsWith("2")) {
                val errBody = response.bodyAsText()
                logger.error("fetchNonStreaming HTTP error: status=${response.status} body=$errBody")
                throw resolveProviderException(response.status.value, errBody)
            }

            val body = response.bodyAsText()
            val content = extractResponseContent(body)
            logger.info("fetchNonStreaming done: model=${modelConfig.model} status=${response.status} contentLen=${content.length} content=$content")
            content
        } catch (e: kotlinx.coroutines.CancellationException) {
            logger.debug("fetchNonStreaming cancelled: model=${modelConfig.model}")
            throw e
        } catch (e: LlmProviderException) {
            throw e
        } catch (e: Exception) {
            logger.error("fetchNonStreaming exception: model=${modelConfig.model}", e)
            throw e
        }
    }

    /**
     * 根据 HTTP 状态码和错误体，推断并构造 [LlmProviderException]。
     * 尝试提取 `error.code` / `error.message`，推断错误类型。
     */
    internal fun resolveProviderException(httpStatus: Int, errBody: String): LlmProviderException {
        val providerMessage = runCatching {
            AppUtil.GlobalVars.json.parseToJsonElement(errBody).jsonObject
                .get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: errBody
        }.getOrElse { errBody }

        val errorCode = runCatching {
            AppUtil.GlobalVars.json.parseToJsonElement(errBody).jsonObject
                .get("error")?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull ?: ""
        }.getOrElse { "" }

        val type = when {
            httpStatus == 401 -> LlmProviderException.Type.AUTH_ERROR
            httpStatus == 429 -> LlmProviderException.Type.RATE_LIMIT
            httpStatus in 500..599 -> LlmProviderException.Type.SERVER_ERROR
            httpStatus == 400 && errorCode == "content_filter" -> LlmProviderException.Type.CONTENT_FILTER
            httpStatus == 404 -> LlmProviderException.Type.MODEL_NOT_FOUND
            httpStatus == 400 && (errorCode.contains("model") || errorCode.contains("not_found")) ->
                LlmProviderException.Type.MODEL_NOT_FOUND
            else -> LlmProviderException.Type.UNKNOWN
        }

        return LlmProviderException(type, httpStatus, providerMessage)
    }

    internal fun normalizeRequestBodyForStreamingCapability(
        requestBody: String,
        supportsStreaming: Boolean,
    ): String {
        val requestJson = AppUtil.GlobalVars.json.parseToJsonElement(requestBody).jsonObject
        return buildJsonObject {
            requestJson.forEach { (key, value) -> put(key, value) }
            put("stream", supportsStreaming)
        }.toString()
    }

    internal fun extractResponseContent(body: String): String {
        val trimmedBody = body.trim()
        if (trimmedBody.isBlank()) {
            return ""
        }
        if (!trimmedBody.startsWith("data:")) {
            return extractMessageContent(trimmedBody)
        }

        val sseContent = buildString {
            trimmedBody.lineSequence().forEach { line ->
                val data = SseLineParser.parseLine(line) ?: return@forEach
                if (data == "[DONE]") return@forEach
                val chunk = extractDeltaContent(data) ?: return@forEach
                append(chunk)
            }
        }
        return sseContent
    }

    private fun extractMessageContent(bodyJson: String): String {
        val choices = AppUtil.GlobalVars.json.parseToJsonElement(bodyJson).jsonObject
            .getValue("choices").jsonArray[0].jsonObject
        val message = choices["message"]
        val content = message?.jsonObject?.getValue("content") ?: choices.getValue("content")
        return content.jsonPrimitive.content
    }


    /**
     * 从单条 SSE `data:` 行的 JSON 内容中提取 `choices[0].delta.content`。
     *
     * 返回 null 的情况：
     * - JSON 解析失败（非标准行）
     * - `choices` 为空数组
     * - `delta` 不含 `content` 字段（如 role-only 的首个 chunk）
     * - `content` 值为 null（部分模型在结束 chunk 中发送 null）
     */
    fun extractDeltaContent(dataJson: String): String? = runCatching {
        AppUtil.GlobalVars.json.parseToJsonElement(dataJson).jsonObject
            .get("choices")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("delta")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull
    }.getOrElse { e ->
        // 解析异常通常是服务端发送了非 JSON 格式的 data 行，降级为 null 跳过即可
        logger.warn("extractDeltaContent parse failed data=$dataJson", isHappensFrequently = true, err = e)
        null
    }
}
