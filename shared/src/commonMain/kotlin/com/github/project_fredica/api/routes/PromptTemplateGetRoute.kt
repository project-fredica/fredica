package com.github.project_fredica.api.routes

// =============================================================================
// PromptTemplateGetRoute —— GET /api/v1/PromptTemplateGetRoute
// =============================================================================
//
// 按 id 返回模板完整信息（含 script_code）。
// 系统模板直接从内存查找，用户模板从 DB 查询。
//
// 查询参数（JSON）：
//   id: string  — 模板 id
// =============================================================================

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.db.PromptTemplateService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.github.project_fredica.auth.AuthRole

object PromptTemplateGetRoute : FredicaApi.Route {
    private val logger = createLogger { "PromptTemplateGetRoute" }

    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "按 id 返回 Prompt 脚本模板完整内容（含 script_code）"
    override val minRole = AuthRole.TENANT

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        return try {
            // GET 路由参数格式为 Map<String, List<String>>，取首元素
            val query = param.loadJsonModel<Map<String, List<String>>>().getOrElse { emptyMap() }
            val id = query["id"]?.firstOrNull()
                ?: return buildJsonObject { put("error", "缺少参数 id") }.toValidJson()
            logger.debug("PromptTemplateGetRoute: id=$id")

            // 先查系统模板（内存），再查用户模板（DB）
            val template = BUILT_IN_PROMPT_TEMPLATES.firstOrNull { it.id == id }
                ?: PromptTemplateService.repo.getById(id)

            if (template == null) {
                logger.debug("PromptTemplateGetRoute: id=$id 不存在")
                return buildJsonObject { put("error", "模板不存在: $id") }.toValidJson()
            }

            AppUtil.dumpJsonStr(template).getOrThrow()
        } catch (e: Throwable) {
            logger.warn("[PromptTemplateGetRoute] 查询模板失败", isHappensFrequently = false, err = e)
            buildJsonObject { put("error", e.message ?: "unknown") }.toValidJson()
        }
    }
}
