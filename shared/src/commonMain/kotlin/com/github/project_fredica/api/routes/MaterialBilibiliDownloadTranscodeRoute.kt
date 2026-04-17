package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.db.MaterialVideoService
import com.github.project_fredica.db.MaterialWorkflowService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object MaterialBilibiliDownloadTranscodeRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.TENANT
    override val desc = "Bilibili 下载 + 转码工作流"

    @Serializable
    data class Param(
        @SerialName("material_id") val materialId: String,
    )

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val p = param.loadJsonModel<Param>().getOrElse {
            return buildJsonObject { put("error", "请求参数无效") }.toValidJson()
        }
        val material = MaterialVideoService.repo.findById(p.materialId)
            ?: return buildJsonObject { put("error", "MATERIAL_NOT_FOUND") }.toValidJson()

        return when (val r = MaterialWorkflowService.startBilibiliDownloadTranscode(material)) {
            is MaterialWorkflowService.StartResult.AlreadyActive ->
                buildJsonObject { put("error", "TASK_ALREADY_ACTIVE") }.toValidJson()
            is MaterialWorkflowService.StartResult.Started ->
                buildJsonObject {
                    put("workflow_run_id", r.workflowRunId)
                    if (r.downloadTaskId != null) put("download_task_id", r.downloadTaskId)
                    if (r.transcodeTaskId != null) put("transcode_task_id", r.transcodeTaskId)
                }.toValidJson()
        }
    }
}
