package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.auth.AuthRole
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.github.project_fredica.db.MaterialVideoService
import com.github.project_fredica.db.toMediaSpec
import com.github.project_fredica.db.WorkflowRun
import com.github.project_fredica.db.WorkflowRunStatusService
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskPriority
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
    override val minRole = AuthRole.TENANT
    override val desc = "为指定素材启动 MP4 转码任务"

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
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
        val spec = material.toMediaSpec()
        val workflowRunId = UUID.randomUUID().toString()
        val taskId = UUID.randomUUID().toString()

        val payload = spec.buildTranscodePayload()
            ?: return buildJsonObject { put("error", "INPUT_NOT_FOUND") }.toValidJson()

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
                priority = TaskPriority.TRANSCODE_MP4,
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
