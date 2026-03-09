package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenRelation
import com.github.project_fredica.db.weben.WebenRelationService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST /api/v1/WebenRelationCreateRoute
 *
 * 手动添加概念关系。is_manual 固定为 true。
 * UNIQUE(subject_id, predicate, object_id) 冲突时更新 confidence。
 */
object WebenRelationCreateRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "手动添加概念关系（is_manual=true，幂等写入）"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<WebenRelationCreateParam>().getOrThrow()
        val nowSec = System.currentTimeMillis() / 1000L
        val relation = WebenRelation(
            id         = java.util.UUID.randomUUID().toString(),
            subjectId  = p.subjectId,
            predicate  = p.predicate,
            objectId   = p.objectId,
            confidence = p.confidence ?: 1.0,
            isManual   = true,
            createdAt  = nowSec,
            updatedAt  = nowSec,
        )
        WebenRelationService.repo.upsert(relation)
        return buildValidJson { kv("ok", true) }
    }
}

@Serializable
data class WebenRelationCreateParam(
    @SerialName("subject_id") val subjectId: String,
    val predicate: String,
    @SerialName("object_id") val objectId: String,
    val confidence: Double? = null,
)
