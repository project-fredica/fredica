package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.MaterialVideoService
import kotlinx.serialization.Serializable

object MaterialDeleteRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "删除素材库中指定的素材"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<MaterialDeleteParam>().getOrThrow()
        MaterialVideoService.repo.deleteByIds(p.ids)
        return ValidJsonString("""{"deleted":${p.ids.size}}""")
    }
}

@Serializable
data class MaterialDeleteParam(
    val ids: List<String>,
)
