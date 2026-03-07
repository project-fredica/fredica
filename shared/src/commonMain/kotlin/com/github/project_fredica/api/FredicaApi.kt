package com.github.project_fredica.api

import com.github.project_fredica.api.routes.getCommonRoutes

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

        suspend fun handler(param: String): Any

        companion object
    }
}

expect suspend fun FredicaApi.Companion.init(options: Any? = null)

expect suspend fun FredicaApi.Companion.getNativeRoutes(): List<FredicaApi.Route>

expect suspend fun FredicaApi.Companion.getNativeWebServerLocalDomainAndPort(): Pair<String, UShort>?

expect suspend fun FredicaApi.PyUtil.get(path: String): String

expect suspend fun FredicaApi.PyUtil.post(path: String, body: String): String

