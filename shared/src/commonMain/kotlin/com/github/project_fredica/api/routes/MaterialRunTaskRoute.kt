package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.MaterialVideoService
import com.github.project_fredica.db.PipelineInstance
import com.github.project_fredica.db.PipelineService
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 为指定素材启动单个初级任务（无 DAG 依赖）。
 *
 * 每次调用创建一个单任务流水线，立即排入 WorkerEngine 队列。
 * 若该素材已有同类型任务处于活跃状态（pending/claimed/running），返回 TASK_ALREADY_ACTIVE 错误。
 */
object MaterialRunTaskRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "为指定素材启动单个初级任务（无 DAG 依赖）"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<MaterialRunTaskParam>().getOrThrow()
        val materialId = p.materialId
        val taskType   = p.taskType

        val material = MaterialVideoService.repo.findById(materialId)
            ?: return buildValidJson { kv("error", "MATERIAL_NOT_FOUND") }

        val activeStatuses = setOf("pending", "claimed", "running")
        val isActive = TaskService.repo.listAll(materialId = materialId, pageSize = 200)
            .items.any { it.type == taskType && it.status in activeStatuses }
        if (isActive) {
            return buildValidJson { kv("error", "TASK_ALREADY_ACTIVE") }
        }

        val nowSec     = System.currentTimeMillis() / 1000L
        val mediaDir   = AppUtil.Paths.materialMediaDir(material.id)
        val pipelineId = UUID.randomUUID().toString()
        val taskId     = UUID.randomUUID().toString()

        val payload = when (taskType) {
            "DOWNLOAD_BILIBILI_VIDEO" -> createJson { obj {
                kv("bvid",        material.sourceId)
                kv("page",        1)
                kv("output_path", mediaDir.resolve("video.mp4").absolutePath)
            } }.toString()
            "TRANSCODE_MP4" -> createJson { obj {
                // Prefer .m4s pair (DASH); fall back to .flv for older downloads
                val videoM4s = mediaDir.resolve("video.m4s")
                val audioM4s = mediaDir.resolve("audio.m4s")
                val videoFlv = mediaDir.resolve("video.flv")
                if (videoM4s.exists() && audioM4s.exists()) {
                    kv("input_video",  videoM4s.absolutePath)
                    kv("input_audio",  audioM4s.absolutePath)
                } else {
                    kv("input_video",  videoFlv.absolutePath)
                }
                kv("output_path", mediaDir.resolve("video.mp4").absolutePath)
                kv("hw_accel",    "auto")
            } }.toString()
            else -> "{}"
        }

        val maxRetries = 3

        PipelineService.repo.create(
            PipelineInstance(
                id         = pipelineId,
                materialId = material.id,
                template   = "manual_${taskType.lowercase()}",
                status     = "pending",
                totalTasks = 1,
                doneTasks  = 0,
                createdAt  = nowSec,
            )
        )
        TaskService.repo.create(
            Task(
                id         = taskId,
                type       = taskType,
                pipelineId = pipelineId,
                materialId = material.id,
                payload    = payload,
                maxRetries = maxRetries,
                createdAt  = nowSec,
            )
        )

        return buildValidJson {
            kv("pipeline_id", pipelineId)
            kv("task_id",     taskId)
        }
    }
}

@Serializable
data class MaterialRunTaskParam(
    @SerialName("material_id") val materialId: String,
    @SerialName("task_type")   val taskType: String,
)
