package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenConceptService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST /api/v1/WebenConceptUpdateRoute
 *
 * 更新概念的定义和元数据（用户手动修正），同步更新 updated_at。
 * mastery 字段为只读缓存，忽略客户端传入的值。
 */
object WebenConceptUpdateRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "更新概念的定义/元数据（用户手动修正）"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<WebenConceptUpdateParam>().getOrThrow()

        val existing = WebenConceptService.repo.getById(p.id)
            ?: return buildValidJson { kv("ok", false); kv("error", "not found") }

        val nowSec = System.currentTimeMillis() / 1000L
        val updated = existing.copy(
            conceptType     = p.conceptType     ?: existing.conceptType,
            briefDefinition = p.briefDefinition ?: existing.briefDefinition,
            metadataJson    = p.metadataJson    ?: existing.metadataJson,
            updatedAt       = nowSec,
        )
        WebenConceptService.repo.update(updated)
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
