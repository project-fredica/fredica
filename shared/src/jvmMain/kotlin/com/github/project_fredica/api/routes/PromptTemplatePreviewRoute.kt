package com.github.project_fredica.api.routes

// =============================================================================
// PromptTemplatePreviewRoute (jvmMain)
// =============================================================================
//
// POST /api/v1/PromptTemplatePreviewRoute
//
// 在 GraalJS 沙箱中执行 Prompt 脚本，以 SSE 流式返回日志与结果。
// 需鉴权，单独注册于 FredicaApi.jvm.kt。
//
// 请求体：PromptScriptExecuteRequest { script_code }
// 响应：text/event-stream，每行格式：data: <JSON>\n\n
//   事件类型：
//     { "type": "log", "level": "log|warn|error", "args": "...", "ts": 123 }
//     { "type": "result", "prompt_text": "..." }
//     { "type": "error",  "error": "...", "error_type": "..." }
// =============================================================================

import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.error
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.prompt.PromptScriptRuntime
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.RoutingContext
import io.ktor.utils.io.writeStringUtf8

object PromptTemplatePreviewRoute {
    private val logger = createLogger { "PromptTemplatePreviewRoute" }

    suspend fun handle(ctx: RoutingContext) {
        val call = ctx.call
        val req = call.receiveText().loadJsonModel<PromptScriptExecuteRequest>().getOrElse { e ->
            // 请求体解析失败属于调用方问题（客户端错误），用 warn 而非 error
            logger.warn("[PromptTemplatePreviewRoute] 请求体解析失败", isHappensFrequently = false, err = e)
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "请求体解析失败"))
            return
        }
        if (req.scriptCode.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "script_code 不能为空"))
            return
        }
        logger.debug("[PromptTemplatePreviewRoute] 执行请求: scriptLength=${req.scriptCode.length}")

        val acquired = PromptScriptRuntime.previewSemaphore.tryAcquire()
        if (!acquired) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "sandbox_busy"))
            return
        }
        try {
            val result = PromptScriptRuntime.execute(req.scriptCode, PromptScriptRuntime.Mode.PREVIEW)

            call.respondBytesWriter(ContentType.Text.EventStream) {
                // 先发所有日志条目
                for (log in result.logs) {
                    val event = buildValidJson {
                        kv("type", "log")
                        kv("level", log.level)
                        kv("args", log.args)
                        kv("ts", log.ts)
                    }
                    writeStringUtf8("data: ${event.str}\n\n")
                    flush()
                }
                // 再发最终结果或错误
                if (result.error != null) {
                    val event = buildValidJson {
                        kv("type", "error")
                        kv("error", result.error)
                        kv("error_type", result.errorType ?: "unknown")
                    }
                    writeStringUtf8("data: ${event.str}\n\n")
                } else {
                    val event = buildValidJson {
                        kv("type", "result")
                        kv("prompt_text", result.promptText ?: "")
                    }
                    writeStringUtf8("data: ${event.str}\n\n")
                }
                flush()
            }
        } finally {
            PromptScriptRuntime.previewSemaphore.release()
        }
    }
}
