package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.auth.GuestInviteLinkService

object GuestInviteLinkListRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val minRole = AuthRole.ROOT
    override val desc = "列出所有游客邀请链接（含访问次数聚合）"

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val list = GuestInviteLinkService.repo.listAll()
        return AppUtil.dumpJsonStr(list).getOrThrow()
    }
}
