package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.MaterialTaskService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

object MaterialTaskListRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "按 material_id 查询素材任务列表（适用于任意素材类型）"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<MaterialTaskListParam>().getOrThrow()
        val tasks = MaterialTaskService.repo.listByMaterialId(p.materialId)
        return ValidJsonString(AppUtil.GlobalVars.json.encodeToString(tasks))
    }
}

@Serializable
data class MaterialTaskListParam(
    @SerialName("material_id") val materialId: String,
)
