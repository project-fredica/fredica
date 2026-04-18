package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.material_category.route_ext.handler2

object MaterialCategorySyncTriggerRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.TENANT
    override val desc = "手动触发同步源同步"

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString = handler2(param, context)
}
