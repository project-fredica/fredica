package com.github.project_fredica.api.routes
//
//import com.github.project_fredica.api.FredicaApi
//import com.github.project_fredica.apputil.AppUtil
//import com.github.project_fredica.apputil.ValidJsonString
//import com.github.project_fredica.apputil.buildValidJson
//import com.github.project_fredica.apputil.createLogger
//import com.github.project_fredica.apputil.loadJsonModel
//import com.github.project_fredica.db.AppConfigService
//import com.github.project_fredica.db.Task
//import com.github.project_fredica.db.TaskStatusService
//import com.github.project_fredica.db.WorkflowRun
//import com.github.project_fredica.db.WorkflowRunStatusService
//import com.github.project_fredica.db.weben.WebenSource
//import com.github.project_fredica.db.weben.WebenSourceService
//import kotlinx.serialization.SerialName
//import kotlinx.serialization.Serializable
//import java.util.UUID
//
///**
// * POST /api/v1/WebenSourceAnalyzeRoute
// *
// * 提交来源进行分析。创建 WebenSource 记录并启动 WorkflowRun 任务链。
// *
// * ## 任务链（fasterWhisperModel 已配置时）
// * ```
// * DOWNLOAD_WHISPER_MODEL (check_skip=true，模型已存在则自动跳过)
// *     ↓
// * FETCH_SUBTITLE
// *     ↓
// * WEBEN_CONCEPT_EXTRACT
// * ```
// *
// * ## 任务链（fasterWhisperModel 未配置时）
// * ```
// * FETCH_SUBTITLE
// *     ↓
// * WEBEN_CONCEPT_EXTRACT
// * ```
// *
// * 注：workflowRunId 提前确定并写入 WebenSource，是为了让
// * WebenSourceListRoute.reconcileSources() 在启动恢复时能够关联对账。
// */
//object WebenSourceAnalyzeRoute : FredicaApi.Route {
//    private val logger = createLogger { "WebenSourceAnalyzeRoute" }
//
//    override val mode = FredicaApi.Route.Mode.Post
//    override val desc = "提交来源进行 AI 分析（FETCH_SUBTITLE → WEBEN_CONCEPT_EXTRACT）"
//
//    override suspend fun handler(param: String): ValidJsonString {
//        val p = param.loadJsonModel<WebenSourceAnalyzeParam>().getOrThrow()
//        val cfg = AppConfigService.repo.getConfig()
//        val nowSec = System.currentTimeMillis() / 1000L
//        val sourceId = UUID.randomUUID().toString()
//        val workflowRunId = UUID.randomUUID().toString()
//
//        logger.debug(
//            "WebenSourceAnalyzeRoute: 提交分析请求 sourceType=${p.sourceType}" +
//            " bvid=${p.bvid} materialId=${p.materialId} title=${p.title}" +
//            " sourceId=$sourceId workflowRunId=$workflowRunId"
//        )
//
//        val source = WebenSource(
//            id             = sourceId,
//            materialId     = p.materialId,
//            url            = p.url,
//            title          = p.title,
//            sourceType     = p.sourceType,
//            bvid           = p.bvid,
//            durationSec    = p.durationSec,
//            qualityScore   = p.qualityScore ?: 0.5,
//            analysisStatus = "pending",
//            workflowRunId  = workflowRunId,
//            createdAt      = nowSec,
//        )
//        WebenSourceService.repo.create(source)
//        logger.info("WebenSourceAnalyzeRoute: WebenSource 已创建 sourceId=$sourceId analysisStatus=pending")
//
//        val webenDir = AppUtil.Paths.webenSourceDir(sourceId).absolutePath
//        val textPath = "$webenDir/source_text.txt"
//
//        // 是否需要插入 DOWNLOAD_WHISPER_MODEL 前置任务
//        val needDownloadTask = cfg.fasterWhisperModel.isNotBlank()
//        val totalTasks = if (needDownloadTask) 3 else 2
//
//        WorkflowRunStatusService.create(
//            WorkflowRun(
//                id         = workflowRunId,
//                materialId = p.materialId ?: "",
//                template   = "weben_analyze",
//                status     = "pending",
//                totalTasks = totalTasks,
//                doneTasks  = 0,
//                createdAt  = nowSec,
//            )
//        )
//        logger.debug("WebenSourceAnalyzeRoute: WorkflowRun 已创建 workflowRunId=$workflowRunId totalTasks=$totalTasks")
//
//        // 可选 Task 0: DOWNLOAD_WHISPER_MODEL（check_skip=true，模型已存在则自动跳过）
//        val fetchSubtitleDependsOn: String
//        if (needDownloadTask) {
//            val downloadTaskId = UUID.randomUUID().toString()
//            val downloadPayload = buildValidJson {
//                kv("model_name", cfg.fasterWhisperModel)
//                kv("proxy", cfg.proxyUrl)
//                kv("check_skip", "true")
//            }.str
//            TaskStatusService.create(
//                Task(
//                    id            = downloadTaskId,
//                    type          = "DOWNLOAD_WHISPER_MODEL",
//                    workflowRunId = workflowRunId,
//                    materialId    = p.materialId ?: "",
//                    payload       = downloadPayload,
//                    idempotencyKey = "DOWNLOAD_WHISPER_MODEL:${cfg.fasterWhisperModel}",
//                    createdAt     = nowSec,
//                )
//            )
//            fetchSubtitleDependsOn = """["$downloadTaskId"]"""
//            logger.debug("WebenSourceAnalyzeRoute: Task0(DOWNLOAD_WHISPER_MODEL) 已创建 model=${cfg.fasterWhisperModel}")
//        } else {
//            fetchSubtitleDependsOn = "[]"
//        }
//
//        // Task 1: FETCH_SUBTITLE
//        val task1Id = UUID.randomUUID().toString()
//        val task1Payload = buildValidJson {
//            kv("source_id",        sourceId)
//            kv("bvid",             p.bvid ?: "")
//            kv("page_index",       0)
//            if (p.materialId != null) kv("material_id", p.materialId)
//            kv("output_text_path", textPath)
//        }.str
//        TaskStatusService.create(
//            Task(
//                id            = task1Id,
//                type          = "FETCH_SUBTITLE",
//                workflowRunId = workflowRunId,
//                materialId    = p.materialId ?: "",
//                dependsOn     = fetchSubtitleDependsOn,
//                payload       = task1Payload,
//                createdAt     = nowSec,
//            )
//        )
//        logger.debug("WebenSourceAnalyzeRoute: Task1(FETCH_SUBTITLE) 已创建 task1Id=$task1Id")
//
//        // Task 2: WEBEN_CONCEPT_EXTRACT（depends_on: [task1Id]）
//        val task2Id = UUID.randomUUID().toString()
//        val task2Payload = buildValidJson {
//            kv("source_id",         sourceId)
//            kv("text_path",         textPath)
//            kv("video_title",       p.title)
//            kv("video_description", "")
//            if (p.materialId != null) kv("material_id", p.materialId)
//        }.str
//        TaskStatusService.create(
//            Task(
//                id            = task2Id,
//                type          = "WEBEN_CONCEPT_EXTRACT",
//                workflowRunId = workflowRunId,
//                materialId    = p.materialId ?: "",
//                dependsOn     = """["$task1Id"]""",
//                payload       = task2Payload,
//                createdAt     = nowSec,
//            )
//        )
//        logger.debug("WebenSourceAnalyzeRoute: Task2(WEBEN_CONCEPT_EXTRACT) 已创建 task2Id=$task2Id")
//
//        logger.info(
//            "WebenSourceAnalyzeRoute: 分析流水线已启动 sourceId=$sourceId" +
//            " workflowRunId=$workflowRunId needDownload=$needDownloadTask"
//        )
//        return buildValidJson {
//            kv("ok", true)
//            kv("source_id", sourceId)
//            kv("workflow_run_id", workflowRunId)
//        }
//    }
//}
//
//@Serializable
//data class WebenSourceAnalyzeParam(
//    @SerialName("material_id")  val materialId:  String? = null,
//    val url: String,
//    val title: String,
//    @SerialName("source_type")  val sourceType:  String,
//    val bvid: String? = null,
//    @SerialName("duration_sec") val durationSec: Double? = null,
//    @SerialName("quality_score") val qualityScore: Double? = null,
//)
