package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.auth.AuthRole
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.github.project_fredica.db.MaterialCategoryService
import kotlinx.serialization.Serializable

object MaterialCategoryDeleteRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.TENANT
    override val desc = "删除素材分类及其视频关联"

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val p = param.loadJsonModel<MaterialCategoryDeleteParam>().getOrThrow()
        MaterialCategoryService.repo.deleteById(p.id)
        return buildJsonObject { put("deleted", true) }.toValidJson()
    }
}

@Serializable
data class MaterialCategoryDeleteParam(
    val id: String,
)
