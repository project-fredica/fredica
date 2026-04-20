package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.auth.AuthRole
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.Serializable

object BilibiliUploaderGetPageRoute : FredicaApi.Route {
    private val logger = createLogger()
    override val mode: FredicaApi.Route.Mode
        get() = FredicaApi.Route.Mode.Post
    override val desc: String
        get() = "获取bilibili UP主指定页的视频列表"
    override val minRole = AuthRole.GUEST

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val p = param.loadJsonModel<BilibiliUploaderGetPageParam>().getOrThrow()
        logger.debug("handler: mid=${p.mid} page=${p.page} order=${p.order}")
        val body = buildJsonObject {
            put("mid", p.mid)
            put("page", p.page)
            put("order", p.order)
        }.toValidJson()
        return ValidJsonString(FredicaApi.PyUtil.post("/bilibili/uploader/get-page", body.str))
    }
}

@Serializable
data class BilibiliUploaderGetPageParam(
    val mid: String,
    val page: Int = 1,
    val order: String = "pubdate",
)
