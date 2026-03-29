package com.github.project_fredica.api.routes

// =============================================================================
// PromptTemplateListRoute —— GET /api/v1/PromptTemplateListRoute
// =============================================================================
//
// 返回系统内置模板与用户模板的合并列表（不含 script_code，节省传输量）。
// 系统模板从 commonMain/resources/prompt_templates/ 加载，不存入 DB。
//
// 查询参数（Map<String, List<String>>）：
//   category?: string  — 按业务分类过滤，留空则返回全部
// =============================================================================

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.db.PromptTemplate
import com.github.project_fredica.db.PromptTemplateService
import com.github.project_fredica.prompt.BuiltInTemplateLoader
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 内置模板列表：由 BuiltInTemplateLoader 从 commonMain/resources/prompt_templates/ 加载。
// 使用 lazy 确保首次访问时才触发 IO，避免静态初始化顺序问题。
val BUILT_IN_PROMPT_TEMPLATES: List<PromptTemplate> by lazy { BuiltInTemplateLoader.loadAll() }

object PromptTemplateListRoute : FredicaApi.Route {
    private val logger = createLogger { "PromptTemplateListRoute" }

    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "返回系统内置 + 用户 Prompt 脚本模板列表（不含 script_code）"

    override suspend fun handler(param: String): ValidJsonString {
        return try {
            // GET 路由参数格式为 Map<String, List<String>>，取 category 首元素
            val query = param.loadJsonModel<Map<String, List<String>>>().getOrElse { emptyMap() }
            val category = query["category"]?.firstOrNull()?.takeIf { it.isNotBlank() }
            logger.debug("PromptTemplateListRoute: category=$category")

            val systemTemplates = if (category == null) {
                BUILT_IN_PROMPT_TEMPLATES
            } else {
                BUILT_IN_PROMPT_TEMPLATES.filter { it.category == category }
            }

            val userTemplates = if (category == null) {
                PromptTemplateService.repo.listUserTemplates()
            } else {
                PromptTemplateService.repo.listUserTemplatesByCategory(category)
            }

            // 合并：系统模板在前，用户模板在后；均去掉 script_code 减少传输量
            val items = (systemTemplates + userTemplates).map { it.toListItem() }

            logger.debug(
                "PromptTemplateListRoute: 返回 system=${systemTemplates.size} user=${userTemplates.size}"
            )
            AppUtil.dumpJsonStr(items).getOrThrow()
        } catch (e: Throwable) {
            logger.warn("[PromptTemplateListRoute] 查询模板列表失败", isHappensFrequently = false, err = e)
            buildValidJson { kv("error", e.message ?: "unknown") }
        }
    }
}

// ── 列表项（省略 script_code） ────────────────────────────────────────────────

@Serializable
data class PromptTemplateListItem(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    @SerialName("source_type") val sourceType: String,
    @SerialName("schema_target") val schemaTarget: String,
    @SerialName("based_on_template_id") val basedOnTemplateId: String?,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
)

private fun PromptTemplate.toListItem() = PromptTemplateListItem(
    id = id,
    name = name,
    description = description,
    category = category,
    sourceType = sourceType,
    schemaTarget = schemaTarget,
    basedOnTemplateId = basedOnTemplateId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
