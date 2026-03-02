package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.PipelineListQuery
import com.github.project_fredica.db.PipelineService
import kotlinx.serialization.encodeToString

/**
 * GET /api/v1/PipelineListRoute
 *
 * 分页查询流水线实例，默认按 created_at 倒序排列。
 *
 * 查询参数（均可选）：
 * - `status`    — 按状态过滤（pending / running / completed / failed / cancelled）
 * - `template`  — 按模板名精确过滤
 * - `page`      — 页码，默认 1
 * - `page_size` — 每页条数（1‒100），默认 20
 *
 * 返回 [com.github.project_fredica.db.PipelinePage] JSON：
 * ```json
 * {
 *   "items": [...],
 *   "total": 42,
 *   "page": 1,
 *   "page_size": 20,
 *   "total_pages": 3
 * }
 * ```
 */
object PipelineListRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "分页查询流水线实例（支持状态/模板过滤，默认按时间倒序）"

    override suspend fun handler(param: String): ValidJsonString {
        val q = param.loadJsonModel<Map<String, List<String>>>().getOrElse { emptyMap() }

        val query = PipelineListQuery(
            status   = q["status"]?.firstOrNull(),
            template = q["template"]?.firstOrNull(),
            page     = q["page"]?.firstOrNull()?.toIntOrNull() ?: 1,
            pageSize = q["page_size"]?.firstOrNull()?.toIntOrNull() ?: 20,
        )

        val page = PipelineService.repo.listPaged(query)
        return ValidJsonString(AppUtil.GlobalVars.json.encodeToString(page))
    }
}
