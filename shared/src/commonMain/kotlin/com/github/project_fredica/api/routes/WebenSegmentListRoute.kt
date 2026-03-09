package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenSegmentService
import kotlinx.serialization.encodeToString

/**
 * GET /api/v1/WebenSegmentListRoute?source_id=<uuid>
 *
 * 查询某视频来源的全部时间段（按 seq 升序），作为播放器时间轴数据。
 */
object WebenSegmentListRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "查询视频来源的全部时间段（播放器时间轴数据，按 seq 升序）"

    override suspend fun handler(param: String): ValidJsonString {
        val query    = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val sourceId = query["source_id"]?.firstOrNull() ?: return ValidJsonString("[]")
        val segments = WebenSegmentService.repo.listBySource(sourceId)
        return ValidJsonString(AppUtil.GlobalVars.json.encodeToString(segments))
    }
}
