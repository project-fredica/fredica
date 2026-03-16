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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * OpenAI 兼容 SSE 流式客户端。
 *
 * 职责：
 * - 向 `{baseUrl}/chat/completions` 发起 POST 请求
 * - 逐行解析 SSE 响应，提取 `choices[0].delta.content` 片段并回调
 * - 支持取消信号（[CompletableDeferred]）：检测到信号完成时立即中断并返回 null
 * - 当模型不含 [LlmCapability.STREAMING] 时自动降级为普通 POST（非流式）
 *
 * 使用 [AppUtil.GlobalVars.ktorClientLocal]（直连，不走代理）。
 */
object LlmSseClient {
    private val logger = createLogger()

    /**
     * 流式调用 OpenAI 兼容 Chat Completions API（SSE）。
     *
     * @param modelConfig  模型配置（含 baseUrl、apiKey、capabilities）
     * @param requestBody  完整请求 JSON 字符串，调用方负责构造（含 messages、stream:true 等字段）
     * @param onChunk      每收到一个 delta.content 片段时的回调，可用于实时更新 UI 或进度
     * @param cancelSignal 取消信号；调用方完成此 Deferred 后，客户端在下一行读取前检测并中断
     * @return 所有 delta 拼接后的完整 content 字符串；若被取消则返回 null
     */
    suspend fun streamChat(
        modelConfig: LlmModelConfig,
        requestBody: String,
        onChunk: ((String) -> Unit)? = null,
        cancelSignal: CompletableDeferred<Unit>? = null,
    ): String? = withContext(Dispatchers.IO) {
        // 模型不支持流式输出时降级为普通 POST，避免 SSE 解析错误
        if (LlmCapability.STREAMING !in modelConfig.capabilities) {
            logger.info("streamChat: model=${modelConfig.model} 不含 STREAMING 能力，降级为非流式请求")
            return@withContext fetchNonStreaming(modelConfig, requestBody)
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
                    // HTTP 非 2xx：记录错误后提前返回，fullContent 保持空
                    val errBody = response.bodyAsText()
                    logger.error("streamChat HTTP error: status=${response.status} body=$errBody")
                    return@execute
                }

                val channel = response.bodyAsChannel()
                var lineCount = 0
                var chunkCount = 0

                while (!channel.isClosedForRead) {
                    // 每行读取前检查取消信号，避免在长流中延迟响应取消
                    if (cancelSignal?.isCompleted == true) {
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
        } catch (e: Exception) {
            logger.error("streamChat exception: model=${modelConfig.model}", e)
            throw e
        }

        // 执行完 execute 块后再次检查取消信号（execute 内 return@execute 不会跳出 withContext）
        if (cancelSignal?.isCompleted == true) {
            logger.info("streamChat cancelled after stream: model=${modelConfig.model}")
            return@withContext null
        }

        logger.debug("streamChat done: model=${modelConfig.model} totalLen=${fullContent.length}")
        fullContent.toString()
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

            val body = response.bodyAsText()
            // 按 OpenAI 响应格式解析：choices[0].message.content
            val content = AppUtil.GlobalVars.json.parseToJsonElement(body).jsonObject
                .getValue("choices").jsonArray[0].jsonObject
                .getValue("message").jsonObject
                .getValue("content").jsonPrimitive.content

            logger.debug("fetchNonStreaming done: model=${modelConfig.model} contentLen=${content.length}")
            content
        } catch (e: Exception) {
            logger.error("fetchNonStreaming exception: model=${modelConfig.model}", e)
            throw e
        }
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
