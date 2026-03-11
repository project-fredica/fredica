package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenRelationService
import kotlinx.serialization.encodeToString

/**
 * GET /api/v1/WebenRelationListRoute?concept_id=<uuid>[&direction=both|out|in]
 *
 * 邻居查询，返回与指定概念相关的关系边，用于图谱邻居展示。
 *
 * direction 参数说明：
 *   out  — 以 concept_id 为主体（subject）的出边（concept → ?）
 *   in   — 以 concept_id 为客体（object）的入边（? → concept）
 *   both — 出边 + 入边（默认），适用于图谱节点展开场景
 */
object WebenRelationListRoute : FredicaApi.Route {
    private val logger = createLogger { "WebenRelationListRoute" }

    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "查询概念的关系边（可选方向：out/in/both）"

    override suspend fun handler(param: String): ValidJsonString {
        val query     = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val conceptId = query["concept_id"]?.firstOrNull()
        val direction = query["direction"]?.firstOrNull() ?: "both"

        // concept_id 为必填；缺失时直接返回空数组
        if (conceptId == null) {
            logger.debug("WebenRelationListRoute: 缺少 concept_id 参数，返回空数组")
            return ValidJsonString("[]")
        }

        logger.debug("WebenRelationListRoute: 查询关系边 conceptId=$conceptId direction=$direction")

        val relations = when (direction) {
            "out"  -> WebenRelationService.repo.listBySubject(conceptId)
            "in"   -> WebenRelationService.repo.listByObject(conceptId)
            // 未知方向值默认 both（宽容处理，避免前端传入未知值时返回空）
            else   -> WebenRelationService.repo.listByConcept(conceptId)
        }

        logger.debug(
            "WebenRelationListRoute: 返回 ${relations.size} 条关系边" +
            " conceptId=$conceptId direction=$direction"
        )
        return ValidJsonString(AppUtil.GlobalVars.json.encodeToString(relations))
    }
}
