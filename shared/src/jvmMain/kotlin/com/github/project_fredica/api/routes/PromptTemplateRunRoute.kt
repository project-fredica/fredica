package com.github.project_fredica.api.routes

// =============================================================================
// PromptTemplateRunRoute (jvmMain)
// =============================================================================
//
// POST /api/v1/PromptTemplateRunRoute
//
// 在 GraalJS 沙箱中执行 Prompt 脚本，返回执行结果 JSON。
// 需鉴权，单独注册于 FredicaApi.jvm.kt。
//
// 请求体：PromptScriptExecuteRequest { script_code }
// 响应：PromptSandboxResult JSON
// =============================================================================

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.error
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.prompt.PromptScriptRuntime
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.http.ContentType
import io.ktor.server.routing.RoutingContext
import com.github.project_fredica.apputil.AppUtil
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** POST /api/v1/PromptTemplateRunRoute 的请求体（Preview 与 Run 共用）。 */
@Serializable
data class PromptScriptExecuteRequest(
    @SerialName("script_code") val scriptCode: String,
)

object PromptTemplateRunRoute {
    private val logger = createLogger { "PromptTemplateRunRoute" }

    suspend fun handle(ctx: RoutingContext) {
        val call = ctx.call
        val req = call.receiveText().loadJsonModel<PromptScriptExecuteRequest>().getOrElse { e ->
            // 请求体解析失败属于调用方问题（客户端错误），用 warn 而非 error
            logger.warn("[PromptTemplateRunRoute] 请求体解析失败", isHappensFrequently = false, err = e)
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "请求体解析失败"))
            return
        }
        if (req.scriptCode.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "script_code 不能为空"))
            return
        }
        logger.debug("[PromptTemplateRunRoute] 执行请求: scriptLength=${req.scriptCode.length}")

        val acquired = PromptScriptRuntime.runSemaphore.tryAcquire()
        if (!acquired) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "sandbox_busy"))
            return
        }
        try {
            val result = PromptScriptRuntime.execute(req.scriptCode, PromptScriptRuntime.Mode.RUN)
            val json = AppUtil.dumpJsonStr(result).getOrElse { e ->
                logger.error("[PromptTemplateRunRoute] 序列化结果失败", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "序列化失败"))
                return
            }
            call.respondText(json.str, ContentType.Application.Json)
        } finally {
            PromptScriptRuntime.runSemaphore.release()
        }
    }
}
