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

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.error
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.prompt.PromptScriptRuntime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** POST /api/v1/PromptTemplateRunRoute 的请求体（Preview 与 Run 共用）。 */
@Serializable
data class PromptScriptExecuteRequest(
    @SerialName("script_code") val scriptCode: String,
)

object PromptTemplateRunRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "Prompt 脚本沙箱执行（GraalJS，返回 JSON）"
    override val minRole = AuthRole.TENANT

    private val logger = createLogger { "PromptTemplateRunRoute" }

    override suspend fun handler(param: String, context: RouteContext): Any {
        val req = param.loadJsonModel<PromptScriptExecuteRequest>().getOrElse { e ->
            logger.warn("[PromptTemplateRunRoute] 请求体解析失败", isHappensFrequently = false, err = e)
            return buildJsonObject { put("error", "请求体解析失败") }.toValidJson()
        }
        if (req.scriptCode.isBlank()) {
            return buildJsonObject { put("error", "script_code 不能为空") }.toValidJson()
        }
        logger.debug("[PromptTemplateRunRoute] 执行请求: scriptLength=${req.scriptCode.length}")

        val acquired = PromptScriptRuntime.runSemaphore.tryAcquire()
        if (!acquired) {
            return buildJsonObject { put("error", "sandbox_busy") }.toValidJson()
        }
        return try {
            val result = PromptScriptRuntime.execute(req.scriptCode, PromptScriptRuntime.Mode.RUN)
            AppUtil.dumpJsonStr(result).getOrElse { e ->
                logger.error("[PromptTemplateRunRoute] 序列化结果失败", e)
                buildJsonObject { put("error", "序列化失败") }.toValidJson()
            }
        } finally {
            PromptScriptRuntime.runSemaphore.release()
        }
    }
}
