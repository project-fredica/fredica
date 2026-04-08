package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.loadJsonModel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.github.project_fredica.db.MaterialVideoService
import com.github.project_fredica.db.WorkflowRun
import com.github.project_fredica.db.WorkflowRunStatusService
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskStatusService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 为指定素材启动 MP4 转码任务。
 *
 * 优先使用 DASH .m4s 对，回退到 .flv。
 * 若该素材已有同类型任务处于活跃状态（pending/claimed/running），返回 TASK_ALREADY_ACTIVE 错误。
 */
object MaterialVideoTranscodeMp4Route : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "为指定素材启动 MP4 转码任务"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<MaterialVideoTranscodeMp4Param>().getOrThrow()
        val materialId = p.materialId
        val taskType = "TRANSCODE_MP4"

        val material = MaterialVideoService.repo.findById(materialId) ?: return buildJsonObject {
            put(
                "error",
                "MATERIAL_NOT_FOUND"
            )
        }.toValidJson()

        val activeStatuses = setOf("pending", "claimed", "running")
        val isActive = TaskStatusService.listAll(
            materialId = materialId,
            pageSize = 200
        ).items.any { it.type == taskType && it.status in activeStatuses }
        if (isActive) {
            return buildJsonObject { put("error", "TASK_ALREADY_ACTIVE") }.toValidJson()
        }

        val nowSec = System.currentTimeMillis() / 1000L
        val mediaDir = AppUtil.Paths.materialMediaDir(material.id)
        val workflowRunId = UUID.randomUUID().toString()
        val taskId = UUID.randomUUID().toString()

        val payload = buildJsonObject {
                // Bilibili 下载路径。
                // Prefer .m4s pair (DASH); fall back to .flv for older downloads
                val videoM4s = mediaDir.resolve("video.m4s")
                val audioM4s = mediaDir.resolve("audio.m4s")
                val videoFlv = mediaDir.resolve("video.flv")
                if (videoM4s.exists() && audioM4s.exists()) {
                    put("input_video", videoM4s.absolutePath)
                    put("input_audio", audioM4s.absolutePath)
                } else {
                    put("input_video", videoFlv.absolutePath)
                }
                put("output_path", mediaDir.resolve("video.mp4").absolutePath)
                put("hw_accel", "auto")
        }.toString()

        WorkflowRunStatusService.create(
            WorkflowRun(
                id = workflowRunId,
                materialId = material.id,
                template = "manual_transcode_mp4",
                status = "pending",
                totalTasks = 1,
                doneTasks = 0,
                createdAt = nowSec,
            )
        )
        TaskStatusService.create(
            Task(
                id = taskId,
                type = taskType,
                workflowRunId = workflowRunId,
                materialId = material.id,
                payload = payload,
                maxRetries = 3,
                createdAt = nowSec,
            )
        )

        return buildJsonObject {
            put("workflow_run_id", workflowRunId)
            put("task_id", taskId)
        }.toValidJson()
    }
}

@Serializable
data class MaterialVideoTranscodeMp4Param(
    @SerialName("material_id") val materialId: String,
)
