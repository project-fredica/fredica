package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.material_category.route_ext.handler2

object MaterialCategorySyncUpdateRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.TENANT
    override val desc = "更新同步分类（仅名称/描述，权限不可修改）"

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString = handler2(param, context)
}
