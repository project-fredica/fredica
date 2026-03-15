package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.MaterialVideoService

object MaterialGetRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "按 ID 查询单个素材"

    override suspend fun handler(param: String): ValidJsonString {
        val query = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val id = query["id"]?.firstOrNull() ?: return ValidJsonString("null")
        val video = MaterialVideoService.repo.findById(id)
        return AppUtil.dumpJsonStr(video).getOrThrow()
    }
}
