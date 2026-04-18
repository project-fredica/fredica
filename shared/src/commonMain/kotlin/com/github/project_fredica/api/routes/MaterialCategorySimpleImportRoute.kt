package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.material_category.route_ext.handler2

object MaterialCategorySimpleImportRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.TENANT
    override val desc = "导入素材并关联分类 / 替换素材的简易分类关联"

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString = handler2(param, context)
}
