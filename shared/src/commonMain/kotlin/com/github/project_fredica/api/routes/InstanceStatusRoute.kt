package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.auth.AuthServiceHolder
import com.github.project_fredica.db.AppConfigService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object InstanceStatusRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "查询实例是否已初始化"
    override val requiresAuth = false
    override val minRole = AuthRole.GUEST

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val initialized = AuthServiceHolder.instance.isInstanceInitialized()
        val guestTokenConfigured = if (initialized) {
            val config = AppConfigService.repo.getConfig()
            config.webserverAuthToken.isNotBlank()
        } else false
        return buildJsonObject {
            put("initialized", initialized)
            put("guest_token_configured", guestTokenConfigured)
        }.toValidJson()
    }
}
