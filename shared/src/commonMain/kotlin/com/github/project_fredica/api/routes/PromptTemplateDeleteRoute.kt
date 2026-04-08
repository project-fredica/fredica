package com.github.project_fredica.api.routes

// =============================================================================
// PromptTemplateDeleteRoute —— POST /api/v1/PromptTemplateDeleteRoute
// =============================================================================
//
// 删除用户模板。
// 请求体：{ "id": "<templateId>" }
//
// 安全约束：
//   - id 以 "sys_" 开头时直接拒绝（系统模板不可删除）
//   - PromptTemplateDb.delete() 内部也做了 source_type 校验（双重防护）
// =============================================================================

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.warn
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.github.project_fredica.db.PromptTemplateService
import kotlinx.serialization.Serializable

object PromptTemplateDeleteRoute : FredicaApi.Route {
    private val logger = createLogger { "PromptTemplateDeleteRoute" }

    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "删除用户 Prompt 脚本模板（系统模板不可删除）"

    @Serializable
    private data class DeleteParam(val id: String)

    override suspend fun handler(param: String): ValidJsonString {
        return try {
            val p = param.loadJsonModel<DeleteParam>().getOrThrow()
            logger.debug("PromptTemplateDeleteRoute: id=${p.id}")

            if (p.id.startsWith("sys_")) {
                logger.warn(
                    "[PromptTemplateDeleteRoute] 拒绝删除系统模板 id=${p.id}",
                    isHappensFrequently = false, err = null
                )
                return buildJsonObject { put("error", "系统模板不可删除") }.toValidJson()
            }

            val deleted = PromptTemplateService.repo.delete(p.id)
            if (!deleted) {
                logger.debug("PromptTemplateDeleteRoute: id=${p.id} 不存在或不可删除")
                return buildJsonObject { put("error", "模板不存在或不可删除: ${p.id}") }.toValidJson()
            }

            logger.info("PromptTemplateDeleteRoute: 已删除 id=${p.id}")
            buildJsonObject {
                put("ok", true)
                put("deleted_id", p.id)
            }.toValidJson()
        } catch (e: Throwable) {
            logger.warn("[PromptTemplateDeleteRoute] 删除模板失败", isHappensFrequently = false, err = e)
            buildJsonObject { put("error", e.message ?: "unknown") }.toValidJson()
        }
    }
}
