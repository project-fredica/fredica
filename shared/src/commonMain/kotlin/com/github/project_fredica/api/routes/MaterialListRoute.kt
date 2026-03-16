package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.db.MaterialVideo
import com.github.project_fredica.db.MaterialVideoService

object MaterialListRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "列出素材库中的所有视频"

    override suspend fun handler(param: String): ValidJsonString {
        val videos: List<MaterialVideo> = MaterialVideoService.repo.listAll()
        return AppUtil.dumpJsonStr(videos).getOrThrow()
    }
}
