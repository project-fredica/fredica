package com.github.project_fredica.api.routes

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.error
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toJsonArray
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.llm.LlmModelConfig
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.RoutingContext
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * LLM 代理聊天路由（SSE 流式转发）。
 *
 * 前端只需传 app_model_id + message，后端查找对应模型的 api_key / base_url，
 * 避免敏感信息暴露给前端或网络中间人。
 *
 * 路由：POST /api/v1/LlmProxyChatRoute（需鉴权）
 * 请求体：[LlmProxyChatRequest]
 * 响应：text/event-stream，原样转发上游 SSE 行
 */
object LlmProxyChatRoute {
    private val logger = createLogger()

    @Serializable
    data class LlmProxyChatRequest(
        @SerialName("app_model_id") val appModelId: String,
        val message: String,
    )

    suspend fun handle(ctx: RoutingContext) {
        val call = ctx.call
        val req = call.receiveText().loadJsonModel<LlmProxyChatRequest>().getOrElse { e ->
            logger.error("LlmProxyChatRoute: 请求体解析失败", e)
            call.response.status(HttpStatusCode.BadRequest)
            return
        }

        // 从 AppConfig 中按 appModelId 查找模型配置，避免前端传入敏感字段
        val config = AppConfigService.repo.getConfig()
        val models = config.llmModelsJson.loadJsonModel<List<LlmModelConfig>>().getOrElse { emptyList() }
        val modelConfig = models.find { it.appModelId == req.appModelId }
        if (modelConfig == null) {
            logger.error("LlmProxyChatRoute: 未找到模型 appModelId=${req.appModelId}")
            call.response.status(HttpStatusCode.NotFound)
            return
        }

        val upstreamUrl = "${modelConfig.baseUrl.trimEnd('/')}/chat/completions"

        // 用 createJson DSL 构建请求体，避免字符串拼接导致的 JSON 注入风险
        val requestBody = createJson {
            obj {
                kv("model", modelConfig.model)
                kv(
                    "messages", listOf(
                    createJson { obj { kv("role", "user"); kv("content", req.message) } }
                ).toJsonArray())
                kv("stream", true)
            }
        }.toString()

        logger.debug("LlmProxyChatRoute: 转发请求到 $upstreamUrl model=${modelConfig.model}")

        try {
            AppUtil.GlobalVars.ktorClientProxied.preparePost(upstreamUrl) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${modelConfig.apiKey}")
                header(HttpHeaders.Accept, "text/event-stream")
                setBody(requestBody)
            }.execute { upstreamResp ->
                if (upstreamResp.status.value !in 200..299) {
                    val errBody = upstreamResp.bodyAsText()
                    logger.error("LlmProxyChatRoute: 上游错误 status=${upstreamResp.status} body=$errBody")
                    call.response.status(upstreamResp.status)
                    call.respondBytesWriter(ContentType.Text.Plain) { writeStringUtf8(errBody) }
                    return@execute
                }

                val channel = upstreamResp.bodyAsChannel()
                var lineCount = 0
                call.respondBytesWriter(ContentType.Text.EventStream) {
                    while (!channel.isClosedForRead) {
                        val line = channel.readUTF8Line() ?: break
                        lineCount++
                        writeStringUtf8(line)
                        writeStringUtf8("\n")
                        flush()
                    }
                    logger.debug("LlmProxyChatRoute: 转发完成，共 $lineCount 行")
                }
            }
        } catch (e: Exception) {
            logger.error("LlmProxyChatRoute: 转发异常", e)
            runCatching { call.response.status(HttpStatusCode.BadGateway) }
        }
    }
}
