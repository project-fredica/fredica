package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenSource
import com.github.project_fredica.db.weben.WebenSourceService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST /api/v1/WebenSourceAnalyzeRoute
 *
 * 提交来源进行分析。Phase A stub：仅创建 WebenSource 记录并标记状态为 'pending'。
 * Phase C 实现完整的 Executor 任务链（FETCH_SUBTITLE → ASR → WEBEN_CONCEPT_EXTRACT）。
 */
object WebenSourceAnalyzeRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "提交来源进行 AI 分析（Phase A stub：仅创建来源记录）"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<WebenSourceAnalyzeParam>().getOrThrow()
        val nowSec = System.currentTimeMillis() / 1000L
        val source = WebenSource(
            id             = java.util.UUID.randomUUID().toString(),
            materialId     = p.materialId,
            url            = p.url,
            title          = p.title,
            sourceType     = p.sourceType,
            bvid           = p.bvid,
            durationSec    = p.durationSec,
            qualityScore   = p.qualityScore ?: 0.5,
            analysisStatus = "pending",
            createdAt      = nowSec,
        )
        WebenSourceService.repo.create(source)
        // TODO Phase C：在此启动 WorkflowRun 任务链
        return buildValidJson { kv("ok", true); kv("source_id", source.id) }
    }
}

@Serializable
data class WebenSourceAnalyzeParam(
    @SerialName("material_id")  val materialId:  String? = null,
    val url: String,
    val title: String,
    @SerialName("source_type")  val sourceType:  String,
    val bvid: String? = null,
    @SerialName("duration_sec") val durationSec: Double? = null,
    @SerialName("quality_score") val qualityScore: Double? = null,
)
