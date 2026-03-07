package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.TaskService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject

/**
 * GET /api/v1/MaterialActiveTasksRoute?material_id=<id>
 *
 * 查询指定素材的任务状态快照：每种 type 只返回创建时间最新的一条，
 * 包含 status、progress、error 等字段，供前端模态框轮询展示进度用。
 */
object MaterialActiveTasksRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "查询素材的任务状态（每种 type 取最新一条）"

    override suspend fun handler(param: String): ValidJsonString {
        val query = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val materialId = query["material_id"]?.firstOrNull()
            ?: return buildValidJson { kv("error", "material_id required") }

        val tasks = TaskService.repo.listAll(materialId = materialId, pageSize = 200).items
        // One latest task per type, sorted by type name for stable display order
        val latest = tasks
            .groupBy { it.type }
            .mapValues { (_, ts) -> ts.maxByOrNull { it.createdAt }!! }
            .values
            .sortedBy { it.type }

        // Strip sensitive fields (payload, result) before sending to frontend
        val stripped = AppUtil.GlobalVars.json.encodeToString(latest).let { raw ->
            AppUtil.GlobalVars.json.parseToJsonElement(raw).let { arr ->
                kotlinx.serialization.json.JsonArray(
                    (arr as kotlinx.serialization.json.JsonArray).map { el ->
                        kotlinx.serialization.json.JsonObject(
                            el.jsonObject.filterKeys { it != "payload" && it != "result" }
                        )
                    }
                )
            }
        }
        return ValidJsonString(AppUtil.GlobalVars.json.encodeToString(stripped))
    }
}
