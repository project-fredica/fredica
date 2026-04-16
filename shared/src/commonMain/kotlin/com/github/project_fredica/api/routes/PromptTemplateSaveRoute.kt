package com.github.project_fredica.api.routes

// =============================================================================
// PromptTemplateSaveRoute —— POST /api/v1/PromptTemplateSaveRoute
// =============================================================================
//
// 保存用户模板（INSERT OR REPLACE）。
// 请求体字段：
//   id: string                       — 新建时由前端生成 UUID；更新时传已有 id
//   name: string
//   description?: string
//   category?: string
//   script_code: string              — 模板脚本正文
//   schema_target?: string
//   based_on_template_id?: string    — 从系统模板复制时填原模板 id
//
// 返回：保存后的完整模板对象。
//
// 安全约束：
//   - source_type 强制为 "user"，不接受调用方指定
//   - id 前缀为 "sys_" 时拒绝写入（系统模板 id 命名空间保留）
// =============================================================================

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.db.PromptTemplate
import com.github.project_fredica.db.PromptTemplateService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object PromptTemplateSaveRoute : FredicaApi.Route {
    private val logger = createLogger { "PromptTemplateSaveRoute" }

    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.TENANT
    override val desc = "新建或更新用户 Prompt 脚本模板"

    @Serializable
    private data class SaveParam(
        val id: String,
        val name: String,
        val description: String = "",
        val category: String = "",
        @SerialName("script_code") val scriptCode: String,
        @SerialName("schema_target") val schemaTarget: String = "",
        @SerialName("based_on_template_id") val basedOnTemplateId: String? = null,
    )

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        return try {
            val p = param.loadJsonModel<SaveParam>().getOrThrow()
            logger.debug("PromptTemplateSaveRoute: id=${p.id} name=${p.name}")

            // 禁止用系统模板命名空间的 id 写用户模板
            if (p.id.startsWith("sys_")) {
                logger.warn(
                    "[PromptTemplateSaveRoute] 拒绝写入：id 以 sys_ 开头 id=${p.id}",
                    isHappensFrequently = false, err = null
                )
                return buildJsonObject { put("error", "id 不能以 sys_ 开头，该命名空间保留给系统模板") }.toValidJson()
            }

            val nowSec = System.currentTimeMillis() / 1000L

            val trimmedName = p.name.trim()
            if (trimmedName.isBlank()) {
                return buildJsonObject { put("error", "模板名称不能为空") }.toValidJson()
            }

            // 若已存在则保留原 created_at；否则以当前时间为准
            val existing = PromptTemplateService.repo.getById(p.id)
            val createdAt = existing?.createdAt ?: nowSec

            val template = PromptTemplate(
                id = p.id,
                name = trimmedName,
                description = p.description,
                category = p.category,
                sourceType = "user",
                scriptLanguage = "javascript",
                scriptCode = p.scriptCode,
                schemaTarget = p.schemaTarget,
                basedOnTemplateId = p.basedOnTemplateId,
                createdAt = createdAt,
                updatedAt = nowSec,
            )

            val saved = PromptTemplateService.repo.save(template)
            logger.info("PromptTemplateSaveRoute: 保存成功 id=${saved.id}")
            AppUtil.dumpJsonStr(saved).getOrThrow()
        } catch (e: Throwable) {
            logger.warn("[PromptTemplateSaveRoute] 保存模板失败", isHappensFrequently = false, err = e)
            buildJsonObject { put("error", e.message ?: "unknown") }.toValidJson()
        }
    }
}
