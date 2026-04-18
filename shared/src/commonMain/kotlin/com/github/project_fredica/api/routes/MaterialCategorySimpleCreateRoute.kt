package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.material_category.route_ext.handler2

object MaterialCategorySimpleCreateRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.TENANT
    override val desc = "创建简易分类（同一用户下名称唯一，已存在则返回已有分类）"

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString = handler2(param, context)
}
