package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenRelation
import com.github.project_fredica.db.weben.WebenRelationService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST /api/v1/WebenRelationCreateRoute
 *
 * 手动添加概念关系边。is_manual 固定为 true，用于区分 LLM 自动提取的关系。
 *
 * 幂等保证：UNIQUE(subject_id, predicate, object_id) 冲突时更新 confidence，
 * 不会产生重复边（DB 层 ON CONFLICT DO UPDATE）。
 */
object WebenRelationCreateRoute : FredicaApi.Route {
    private val logger = createLogger { "WebenRelationCreateRoute" }

    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "手动添加概念关系（is_manual=true，幂等写入）"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<WebenRelationCreateParam>().getOrThrow()
        val nowSec = System.currentTimeMillis() / 1000L

        logger.debug(
            "WebenRelationCreateRoute: 手动添加关系" +
            " subject=${p.subjectId} predicate=${p.predicate} object=${p.objectId}" +
            " confidence=${p.confidence ?: 1.0}"
        )

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

        logger.info(
            "WebenRelationCreateRoute: 关系已写入（upsert）" +
            " id=${relation.id} subject=${p.subjectId} predicate=${p.predicate} object=${p.objectId}"
        )
        return buildValidJson { kv("ok", true) }
    }
}

@Serializable
data class WebenRelationCreateParam(
    @SerialName("subject_id") val subjectId: String,
    val predicate: String,
    @SerialName("object_id") val objectId: String,
    /** 关系置信度，手动添加默认为 1.0（确定性关系）。 */
    val confidence: Double? = null,
)
