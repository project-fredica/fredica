package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenSourceService
import kotlinx.serialization.encodeToString

/**
 * GET /api/v1/WebenSourceListRoute[?material_id=<uuid>]
 *
 * 来源库列表，可按 material_id 过滤（不传则返回所有），按创建时间降序。
 */
object WebenSourceListRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "来源库列表（可按 material_id 过滤）"

    override suspend fun handler(param: String): ValidJsonString {
        val query      = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val materialId = query["material_id"]?.firstOrNull()
        val sources    = WebenSourceService.repo.listAll(materialId)
        return ValidJsonString(AppUtil.GlobalVars.json.encodeToString(sources))
    }
}
