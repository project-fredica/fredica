package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.json
import com.github.project_fredica.db.MaterialCategory
import com.github.project_fredica.db.MaterialCategoryService
import kotlinx.serialization.encodeToString

object MaterialCategoryListRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "列出所有素材分类（含视频数量）"

    override suspend fun handler(param: String): ValidJsonString {
        val categories: List<MaterialCategory> = MaterialCategoryService.repo.listAll()
        return ValidJsonString(AppUtil.GlobalVars.json.encodeToString(categories))
    }
}
