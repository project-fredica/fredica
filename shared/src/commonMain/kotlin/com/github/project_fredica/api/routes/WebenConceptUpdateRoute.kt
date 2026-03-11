package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenConceptService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST /api/v1/WebenConceptUpdateRoute
 *
 * 更新概念的定义和元数据（用户手动修正），同步更新 updated_at。
 *
 * 说明：
 *   - 所有字段均为可选；传 null 则保留原值（PATCH 语义）
 *   - mastery 字段是 SM-2 算法维护的只读缓存，忽略客户端传入的任何值
 *   - canonical_name 不可通过此接口修改（影响去重逻辑）
 */
object WebenConceptUpdateRoute : FredicaApi.Route {
    private val logger = createLogger { "WebenConceptUpdateRoute" }

    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "更新概念的定义/元数据（用户手动修正，PATCH 语义）"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<WebenConceptUpdateParam>().getOrThrow()

        logger.debug(
            "WebenConceptUpdateRoute: 更新请求 id=${p.id}" +
            " conceptType=${p.conceptType} briefDefinition=${p.briefDefinition?.take(30)}" +
            " metadataJson=${if (p.metadataJson != null) "(provided)" else "null"}"
        )

        val existing = WebenConceptService.repo.getById(p.id)
        if (existing == null) {
            logger.debug("WebenConceptUpdateRoute: 概念不存在 id=${p.id}，返回 not found")
            return buildValidJson { kv("ok", false); kv("error", "not found") }
        }

        val nowSec = System.currentTimeMillis() / 1000L
        val updated = existing.copy(
            conceptType     = p.conceptType     ?: existing.conceptType,
            briefDefinition = p.briefDefinition ?: existing.briefDefinition,
            metadataJson    = p.metadataJson    ?: existing.metadataJson,
            updatedAt       = nowSec,
        )
        WebenConceptService.repo.update(updated)

        logger.info(
            "WebenConceptUpdateRoute: 概念已更新 id=${p.id}" +
            " conceptType=${updated.conceptType}"
        )
        return buildValidJson { kv("ok", true) }
    }
}

@Serializable
data class WebenConceptUpdateParam(
    val id: String,
    @SerialName("concept_type")     val conceptType:     String? = null,
    @SerialName("brief_definition") val briefDefinition: String? = null,
    @SerialName("metadata_json")    val metadataJson:    String? = null,
)
