package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.auth.AuthIdentity
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.auth.UserRecord
import com.github.project_fredica.auth.UserService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object UserListRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val minRole = AuthRole.ROOT
    override val desc = "列出所有用户（仅 ROOT）"

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val identity = context.identity
        if (identity !is AuthIdentity.RootUser) {
            return buildJsonObject { put("error", "权限不足，仅 ROOT 可查看用户列表") }.toValidJson()
        }

        val entities = UserService.repo.listAll()
        val users = entities.map { UserRecord.fromEntity(it) }
        return AppUtil.dumpJsonStr(users).getOrThrow()
    }
}
