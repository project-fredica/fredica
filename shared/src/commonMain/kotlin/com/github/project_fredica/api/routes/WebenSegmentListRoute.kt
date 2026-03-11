package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenSegmentService
import kotlinx.serialization.encodeToString

/**
 * GET /api/v1/WebenSegmentListRoute?source_id=<uuid>
 *
 * 查询某视频来源的全部时间段（按 seq 升序），作为播放器时间轴数据。
 *
 * 每个 WebenSegment 对应一个文本切块区间，包含：
 *   seq       — 切块序号（0-based）
 *   start_sec / end_sec — 视频时间范围（秒）
 *   concepts  — 该区间关联的概念 id 列表（通过 weben_segment_concept 表关联）
 */
object WebenSegmentListRoute : FredicaApi.Route {
    private val logger = createLogger { "WebenSegmentListRoute" }

    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "查询视频来源的全部时间段（播放器时间轴数据，按 seq 升序）"

    override suspend fun handler(param: String): ValidJsonString {
        val query    = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val sourceId = query["source_id"]?.firstOrNull()

        // source_id 为必填；缺失时返回空数组
        if (sourceId == null) {
            logger.debug("WebenSegmentListRoute: 缺少 source_id 参数，返回空数组")
            return ValidJsonString("[]")
        }

        logger.debug("WebenSegmentListRoute: 查询时间段列表 sourceId=$sourceId")
        val segments = WebenSegmentService.repo.listBySource(sourceId)
        logger.debug("WebenSegmentListRoute: 返回 ${segments.size} 个时间段 sourceId=$sourceId")
        return ValidJsonString(AppUtil.GlobalVars.json.encodeToString(segments))
    }
}
