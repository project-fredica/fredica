package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.json
import com.github.project_fredica.db.MaterialVideoService
import kotlinx.serialization.encodeToString

object MaterialGetRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "按 ID 查询单个素材"

    override suspend fun handler(param: String): ValidJsonString {
        val query = AppUtil.GlobalVars.json.decodeFromString<Map<String, List<String>>>(param)
        val id = query["id"]?.firstOrNull() ?: return ValidJsonString("null")
        val video = MaterialVideoService.repo.findById(id)
        return ValidJsonString(AppUtil.GlobalVars.json.encodeToString(video))
    }
}
