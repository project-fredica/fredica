package com.github.project_fredica.api

import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.api.routes.getCommonRoutes
import com.github.project_fredica.auth.AuthRole

interface FredicaApi {
    companion object {
        suspend fun getAllRoutes(): List<Route> {
            return listOf(
                *Route.getCommonRoutes().toTypedArray(), *getNativeRoutes().toTypedArray()
            ).sortedBy { route -> route.name }
        }

        const val DEFAULT_DEV_WEBUI_PORT: UShort = 7630u
        const val DEFAULT_KTOR_SERVER_PORT: UShort = 7631u
        const val DEFAULT_PYUTIL_SERVER_PORT: UShort = 7632u
    }

    object PyUtil

    interface Route {
        enum class Mode {
            Get, Post
        }

        val name: String get() = this::class.simpleName!!
        val mode: Mode
        val desc: String
        val requiresAuth: Boolean get() = true

        /** 允许访问此路由的最低角色。每个路由必须显式声明。 */
        val minRole: AuthRole

        /** 需要的额外权限标签（仅对 TENANT/ROOT 生效）。 */
        val requiredPermissions: Set<String> get() = emptySet()

        suspend fun handler(param: String, context: RouteContext): Any

        companion object
    }
}

expect suspend fun FredicaApi.Companion.init(options: Any? = null)

expect suspend fun FredicaApi.Companion.getNativeRoutes(): List<FredicaApi.Route>

expect suspend fun FredicaApi.Companion.getNativeWebServerLocalDomainAndPort(): Pair<String, UShort>?

expect suspend fun FredicaApi.PyUtil.get(path: String): String

expect suspend fun FredicaApi.PyUtil.post(path: String, body: String, timeoutMs: Long = 60_000L): String

