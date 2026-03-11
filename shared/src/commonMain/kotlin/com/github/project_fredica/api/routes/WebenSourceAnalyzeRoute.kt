package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskStatusService
import com.github.project_fredica.db.WorkflowRun
import com.github.project_fredica.db.WorkflowRunStatusService
import com.github.project_fredica.db.weben.WebenSource
import com.github.project_fredica.db.weben.WebenSourceService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * POST /api/v1/WebenSourceAnalyzeRoute
 *
 * 提交来源进行分析。创建 WebenSource 记录并启动 WorkflowRun 任务链：
 *   Task 1: FETCH_SUBTITLE     — 获取/生成字幕文本
 *   Task 2: WEBEN_CONCEPT_EXTRACT（depends_on: [Task1]）— LLM 概念抽取写图谱
 *
 * ## 数据流
 * 1. 解析参数 → 生成 sourceId / workflowRunId / task1Id / task2Id（全部 UUID）
 * 2. INSERT weben_source（analysis_status='pending'，关联 workflowRunId）
 * 3. INSERT workflow_run（total_tasks=2）
 * 4. INSERT task: FETCH_SUBTITLE（无前置依赖）
 * 5. INSERT task: WEBEN_CONCEPT_EXTRACT（depends_on=[task1Id]，DAG 串行）
 * 6. 返回 { ok, source_id, workflow_run_id }
 *
 * 注：workflowRunId 提前确定并写入 WebenSource，是为了让
 * WebenSourceListRoute.reconcileSources() 在启动恢复时能够关联对账。
 */
object WebenSourceAnalyzeRoute : FredicaApi.Route {
    private val logger = createLogger { "WebenSourceAnalyzeRoute" }

    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "提交来源进行 AI 分析（FETCH_SUBTITLE → WEBEN_CONCEPT_EXTRACT）"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<WebenSourceAnalyzeParam>().getOrThrow()
        val nowSec = System.currentTimeMillis() / 1000L
        val sourceId = UUID.randomUUID().toString()

        // 提前生成 workflowRunId，用于关联 WebenSource（启动恢复时对账用）
        val workflowRunId = UUID.randomUUID().toString()
        val task1Id = UUID.randomUUID().toString()
        val task2Id = UUID.randomUUID().toString()

        logger.debug(
            "WebenSourceAnalyzeRoute: 提交分析请求 sourceType=${p.sourceType}" +
            " bvid=${p.bvid} materialId=${p.materialId} title=${p.title}" +
            " sourceId=$sourceId workflowRunId=$workflowRunId"
        )

        val source = WebenSource(
            id             = sourceId,
            materialId     = p.materialId,
            url            = p.url,
            title          = p.title,
            sourceType     = p.sourceType,
            bvid           = p.bvid,
            durationSec    = p.durationSec,
            qualityScore   = p.qualityScore ?: 0.5,
            analysisStatus = "pending",
            workflowRunId  = workflowRunId,
            createdAt      = nowSec,
        )
        WebenSourceService.repo.create(source)
        logger.info("WebenSourceAnalyzeRoute: WebenSource 已创建 sourceId=$sourceId analysisStatus=pending")

        // 构建文件路径（weben_source_dir/{sourceId}/source_text.txt）
        val webenDir = AppUtil.Paths.webenSourceDir(sourceId).absolutePath
        val textPath = "$webenDir/source_text.txt"
        logger.debug("WebenSourceAnalyzeRoute: 输出文本路径 textPath=$textPath")

        // 创建 WorkflowRun（2 任务：FETCH_SUBTITLE → WEBEN_CONCEPT_EXTRACT）
        WorkflowRunStatusService.create(
            WorkflowRun(
                id         = workflowRunId,
                materialId = p.materialId ?: "",
                template   = "weben_analyze",
                status     = "pending",
                totalTasks = 2,
                doneTasks  = 0,
                createdAt  = nowSec,
            )
        )
        logger.debug("WebenSourceAnalyzeRoute: WorkflowRun 已创建 workflowRunId=$workflowRunId totalTasks=2")

        // Task 1: FETCH_SUBTITLE（无前置依赖，立即可被 claimNext 认领）
        val task1Payload = buildValidJson {
            kv("source_id",        sourceId)
            kv("bvid",             p.bvid ?: "")
            kv("page",             1)
            if (p.materialId != null) kv("material_id", p.materialId)
            kv("output_text_path", textPath)
        }.str

        TaskStatusService.create(
            Task(
                id            = task1Id,
                type          = "FETCH_SUBTITLE",
                workflowRunId = workflowRunId,
                materialId    = p.materialId ?: "",
                payload       = task1Payload,
                createdAt     = nowSec,
            )
        )
        logger.debug("WebenSourceAnalyzeRoute: Task1(FETCH_SUBTITLE) 已创建 task1Id=$task1Id")

        // Task 2: WEBEN_CONCEPT_EXTRACT（depends_on: [task1Id]，等 Task1 完成后才可认领）
        val task2Payload = buildValidJson {
            kv("source_id",         sourceId)
            kv("text_path",         textPath)
            kv("video_title",       p.title)
            kv("video_description", "")
            if (p.materialId != null) kv("material_id", p.materialId)
        }.str

        TaskStatusService.create(
            Task(
                id            = task2Id,
                type          = "WEBEN_CONCEPT_EXTRACT",
                workflowRunId = workflowRunId,
                materialId    = p.materialId ?: "",
                dependsOn     = """["$task1Id"]""",
                payload       = task2Payload,
                createdAt     = nowSec,
            )
        )
        logger.debug("WebenSourceAnalyzeRoute: Task2(WEBEN_CONCEPT_EXTRACT) 已创建 task2Id=$task2Id dependsOn=[$task1Id]")

        logger.info(
            "WebenSourceAnalyzeRoute: 分析流水线已启动 sourceId=$sourceId" +
            " workflowRunId=$workflowRunId task1=$task1Id task2=$task2Id"
        )
        return buildValidJson {
            kv("ok", true)
            kv("source_id", sourceId)
            kv("workflow_run_id", workflowRunId)
        }
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
