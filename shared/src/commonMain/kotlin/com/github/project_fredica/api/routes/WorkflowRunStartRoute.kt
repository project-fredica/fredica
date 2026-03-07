package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.MaterialVideo
import com.github.project_fredica.db.MaterialVideoService
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskService
import com.github.project_fredica.db.WorkflowRun
import com.github.project_fredica.db.WorkflowRunService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 按模板启动多任务 WorkflowRun（支持 DAG 依赖）。
 *
 * 当前支持模板：
 *   - `bilibili_download_transcode`：下载 + 转码两步流水线，下载任务携带 check_skip=true，
 *     重复触发时若下载结果已存在（.done 文件）则自动跳过下载直接转码。
 */
object WorkflowRunStartRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "按模板启动多任务 WorkflowRun（支持 DAG 依赖）"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<WorkflowRunStartParam>().getOrThrow()
        val material = MaterialVideoService.repo.findById(p.materialId)
            ?: return buildValidJson { kv("error", "MATERIAL_NOT_FOUND") }
        return when (p.template) {
            "bilibili_download_transcode" -> startBilibiliDownloadTranscode(material)
            else -> buildValidJson { kv("error", "UNKNOWN_TEMPLATE") }
        }
    }

    private suspend fun startBilibiliDownloadTranscode(material: MaterialVideo): ValidJsonString {
        val activeStatuses = setOf("pending", "claimed", "running")
        val hasActive = TaskService.repo.listAll(materialId = material.id, pageSize = 200)
            .items.any { it.type in setOf("DOWNLOAD_BILIBILI_VIDEO", "TRANSCODE_MP4") && it.status in activeStatuses }
        if (hasActive) return buildValidJson { kv("error", "TASK_ALREADY_ACTIVE") }

        val nowSec          = System.currentTimeMillis() / 1000L
        val mediaDir        = AppUtil.Paths.materialMediaDir(material.id)
        val workflowRunId   = UUID.randomUUID().toString()
        val downloadTaskId  = UUID.randomUUID().toString()
        val transcodeTaskId = UUID.randomUUID().toString()

        val downloadPayload = createJson { obj {
            kv("bvid",        material.sourceId)
            kv("page",        1)
            kv("output_path", mediaDir.resolve("video.mp4").absolutePath)
            kv("check_skip",  true)
        } }.toString()

        val transcodePayload = createJson { obj {
            kv("mode",        "from_bilibili_download")
            kv("output_dir",  mediaDir.absolutePath)
            kv("output_path", mediaDir.resolve("video.mp4").absolutePath)
            kv("hw_accel",    "auto")
            kv("check_skip",  true)
        } }.toString()

        WorkflowRunService.repo.create(WorkflowRun(
            id         = workflowRunId,
            materialId = material.id,
            template   = "bilibili_download_transcode",
            status     = "pending",
            totalTasks = 2,
            doneTasks  = 0,
            createdAt  = nowSec,
        ))
        TaskService.repo.createAll(listOf(
            Task(
                id            = downloadTaskId,
                type          = "DOWNLOAD_BILIBILI_VIDEO",
                workflowRunId = workflowRunId,
                materialId    = material.id,
                payload       = downloadPayload,
                maxRetries    = 3,
                createdAt     = nowSec,
            ),
            Task(
                id            = transcodeTaskId,
                type          = "TRANSCODE_MP4",
                workflowRunId = workflowRunId,
                materialId    = material.id,
                payload       = transcodePayload,
                dependsOn     = """["$downloadTaskId"]""",
                maxRetries    = 3,
                createdAt     = nowSec,
            ),
        ))

        return buildValidJson {
            kv("workflow_run_id",   workflowRunId)
            kv("download_task_id",  downloadTaskId)
            kv("transcode_task_id", transcodeTaskId)
        }
    }
}

@Serializable
data class WorkflowRunStartParam(
    @SerialName("material_id") val materialId: String,
    val template: String,
)
