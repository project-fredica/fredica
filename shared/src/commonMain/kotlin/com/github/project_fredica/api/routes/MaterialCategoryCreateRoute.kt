package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.MaterialCategory
import com.github.project_fredica.db.MaterialCategoryService
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

object MaterialCategoryCreateRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "创建素材分类（名称已存在则返回已有分类）"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<MaterialCategoryCreateParam>().getOrThrow()
        val category: MaterialCategory = MaterialCategoryService.repo.createOrGet(p.name.trim(), p.description)
        return ValidJsonString(AppUtil.GlobalVars.json.encodeToString(category))
    }
}

@Serializable
data class MaterialCategoryCreateParam(
    val name: String,
    val description: String = "",
)
