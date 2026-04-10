package com.github.project_fredica.db

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.asr.material_workflow_ext.startWhisperTranscribe2
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 素材工作流业务服务，封装各模板的类型化参数构建、幂等检查和工作流查询。
 *
 * 实际的 WorkflowRun / Task DB 写入由 [CommonWorkflowService] 完成；
 * 路由层委托此服务完成业务处理，自身只做 HTTP 解析/响应。
 *
 * 新增模板时在此处添加对应的 start*() 方法即可。
 */
object MaterialWorkflowService {

    private val ACTIVE_STATUSES = setOf("pending", "claimed", "running")

    // ── 结果类型 ───────────────────────────────────────────────────────────────

    sealed interface StartResult {
        /** 已有活跃任务，不重复创建。 */
        data object AlreadyActive : StartResult

        /** 成功创建，返回各任务 ID（String，供 HTTP 层直接序列化为 JSON）。 */
        data class Started(
            val workflowRunId: String,
            val downloadTaskId: String? = null,
            val transcodeTaskId: String? = null,
            val extractAudioTaskId: String? = null,
            val transcribeTaskId: String? = null,
            val spawnChunksTaskId: String? = null,
        ) : StartResult
    }

    /**
     * WorkflowRun 及其子任务的聚合视图。
     *
     * [queryActive] 和 [queryHistory] 均返回此类型，前端通过 [run] 获取整体进度，
     * 通过 [tasks] 获取每个子任务的状态/进度/错误信息。
     */
    @Serializable
    data class WorkflowRunWithTasks(
        @SerialName("workflow_run") val run: WorkflowRun,
        val tasks: List<Task>,
    )

    /**
     * [queryActive] 的返回值：当前素材所有活跃工作流（非终态）。
     */
    @Serializable
    data class ActiveWorkflowsResult(
        @SerialName("workflow_runs") val workflowRuns: List<WorkflowRunWithTasks>,
    )

    /**
     * [queryHistory] 的返回值：当前素材已终态工作流（分页）。
     */
    @Serializable
    data class WorkflowHistoryResult(
        val items: List<WorkflowRunWithTasks>,
        val total: Int,
    )

    // ── bilibili_download_transcode ────────────────────────────────────────────

    /**
     * 启动 bilibili 下载 + 转码两步流水线。
     *
     * 任务链：
     *   DOWNLOAD_BILIBILI_VIDEO (check_skip=true) → TRANSCODE_MP4 (from_bilibili_download)
     *
     * 幂等性：若已有活跃的 DOWNLOAD_BILIBILI_VIDEO 或 TRANSCODE_MP4 任务，
     * 返回 [StartResult.AlreadyActive]。
     */
    suspend fun startBilibiliDownloadTranscode(material: MaterialVideo): StartResult {
        val hasActive = TaskStatusService.listAll(materialId = material.id, pageSize = 200)
            .items.any {
                it.type in setOf("DOWNLOAD_BILIBILI_VIDEO", "TRANSCODE_MP4") &&
                it.status in ACTIVE_STATUSES
            }
        if (hasActive) return StartResult.AlreadyActive

        val mediaDir        = AppUtil.Paths.materialMediaDir(material.id)
        val downloadTaskId  = TaskId.random()
        val transcodeTaskId = TaskId.random()

        val downloadPayload = buildJsonObject {
            put("bvid",        material.sourceId)
            put("page",        1)
            put("output_path", mediaDir.resolve("video.mp4").absolutePath)
            put("check_skip",  true)
        }.toString()

        val transcodePayload = buildJsonObject {
            put("mode",        "from_bilibili_download")
            put("output_dir",  mediaDir.absolutePath)
            put("output_path", mediaDir.resolve("video.mp4").absolutePath)
            put("hw_accel",    "auto")
            put("check_skip",  true)
        }.toString()

        val workflowRunId = CommonWorkflowService.createWorkflow(
            template   = "bilibili_download_transcode",
            materialId = material.id,
            tasks      = listOf(
                CommonWorkflowService.TaskDef(
                    id         = downloadTaskId,
                    type       = "DOWNLOAD_BILIBILI_VIDEO",
                    materialId = material.id,
                    payload    = downloadPayload,
                    maxRetries = 3,
                ),
                CommonWorkflowService.TaskDef(
                    id           = transcodeTaskId,
                    type         = "TRANSCODE_MP4",
                    materialId   = material.id,
                    payload      = transcodePayload,
                    dependsOnIds = listOf(downloadTaskId),
                    maxRetries   = 3,
                ),
            ),
        )

        return StartResult.Started(
            workflowRunId   = workflowRunId,
            downloadTaskId  = downloadTaskId.value,
            transcodeTaskId = transcodeTaskId.value,
        )
    }

    // ── whisper_transcribe ────────────────────────────────────────────────────

    /** 委托到 [startWhisperTranscribe2] 扩展函数（asr/material_workflow_ext）。 */
    suspend fun startWhisperTranscribe(
        material: MaterialVideo,
        model: String? = null,
        language: String? = null,
        allowDownload: Boolean = false,
    ): StartResult = startWhisperTranscribe2(material, model, language, allowDownload)

    // ── 工作流查询 ────────────────────────────────────────────────────────────

    /**
     * 查询素材的**活跃工作流**（非终态，不分页）。
     *
     * 活跃 = status 为 pending / running（不含 completed / failed / cancelled）。
     * 每个 WorkflowRun 附带其所有子任务，供前端展示实时进度。
     *
     * 触发首次启动对账：调用前先通过 [TaskStatusService.listAll] 确保
     * WorkflowRun 的汇总状态与 Task 实际状态一致（[StartupReconcileGuard] 保护）。
     *
     * @param materialId 素材 ID
     * @return [ActiveWorkflowsResult]（列表可能为空）
     */
    suspend fun queryActive(materialId: String): ActiveWorkflowsResult {
        // 触发 material 维度的启动级对账（每次 APP 启动只执行一次）
        TaskStatusService.listAll(materialId = materialId, pageSize = 1)

        val runs = WorkflowRunService.repo.listActiveByMaterial(materialId)
        val items = runs.map { run ->
            WorkflowRunWithTasks(
                run   = run,
                tasks = TaskService.repo.listByWorkflowRun(run.id),
            )
        }
        return ActiveWorkflowsResult(workflowRuns = items)
    }

    /**
     * 查询素材的**历史工作流**（终态，分页）。
     *
     * 历史 = status 为 completed / failed / cancelled。
     * 每个 WorkflowRun 附带其所有子任务，按创建时间降序排列。
     *
     * 终态 WorkflowRun 不会再变更，无需触发对账。
     *
     * @param materialId 素材 ID
     * @param page       页码（从 1 开始）
     * @param pageSize   每页条数（1–100）
     * @return [WorkflowHistoryResult]（含 items 和 total）
     */
    suspend fun queryHistory(
        materialId: String,
        page: Int = 1,
        pageSize: Int = 10,
    ): WorkflowHistoryResult {
        val result = WorkflowRunService.repo.listHistoryByMaterial(materialId, page, pageSize)
        val items = result.items.map { run ->
            WorkflowRunWithTasks(
                run   = run,
                tasks = TaskService.repo.listByWorkflowRun(run.id),
            )
        }
        return WorkflowHistoryResult(items = items, total = result.total)
    }
}
