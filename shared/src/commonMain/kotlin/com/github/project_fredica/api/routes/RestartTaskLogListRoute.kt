package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.RestartTaskLogListResult
import com.github.project_fredica.db.RestartTaskLogService

/**
 * GET /api/v1/RestartTaskLogListRoute[?disposition=...&material_id=...]
 *
 * 查询重启中断任务日志。
 *
 * 参数：
 *   disposition  — 按处置状态过滤（pending_review / dismissed / recreated / superseded）；省略则返回全部
 *   material_id  — 按素材 ID 过滤；省略则返回全部
 *
 * 响应：{ "items": [...], "pending_review_count": N }
 */
object RestartTaskLogListRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "查询重启中断任务日志，含 pending_review 数量角标"

    override suspend fun handler(param: String): ValidJsonString {
        val query      = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val disposition = query["disposition"]?.firstOrNull()
        val materialId  = query["material_id"]?.firstOrNull()

        val items              = RestartTaskLogService.repo.listAll(disposition, materialId)
        val pendingReviewCount = RestartTaskLogService.repo.countPendingReview()

        val result = RestartTaskLogListResult(items = items, pendingReviewCount = pendingReviewCount)
        return ValidJsonString(AppUtil.GlobalVars.json.encodeToString(result))
    }
}
