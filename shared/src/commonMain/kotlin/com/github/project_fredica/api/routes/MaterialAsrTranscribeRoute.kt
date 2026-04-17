package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.asr.service.AsrConfigService
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.db.MaterialVideoService
import com.github.project_fredica.db.MaterialWorkflowService
import com.github.project_fredica.db.TaskPriority
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object MaterialAsrTranscribeRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.TENANT
    override val desc = "ASR 语音识别工作流（Whisper）"

    @Serializable
    data class Param(
        @SerialName("material_id") val materialId: String,
        val model: String? = null,
        val language: String? = null,
        @SerialName("allow_download") val allowDownload: Boolean = false,
        val priority: String? = null,
    )

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val p = param.loadJsonModel<Param>().getOrElse {
            return buildJsonObject { put("error", "请求参数无效") }.toValidJson()
        }
        val material = MaterialVideoService.repo.findById(p.materialId)
            ?: return buildJsonObject { put("error", "MATERIAL_NOT_FOUND") }.toValidJson()

        if (p.model.isNullOrBlank()) {
            return buildJsonObject { put("error", "MODEL_REQUIRED") }.toValidJson()
        }
        if (!AsrConfigService.isModelAllowed(p.model)) {
            return buildJsonObject { put("error", "MODEL_NOT_ALLOWED") }.toValidJson()
        }

        val effectiveAllowDownload = AsrConfigService.isDownloadAllowed() && p.allowDownload

        return when (val r = MaterialWorkflowService.startWhisperTranscribe(
            material = material,
            model = p.model,
            language = p.language,
            allowDownload = effectiveAllowDownload,
            priority = TaskPriority.asrPriority(p.priority),
        )) {
            is MaterialWorkflowService.StartResult.AlreadyActive ->
                buildJsonObject { put("error", "TASK_ALREADY_ACTIVE") }.toValidJson()
            is MaterialWorkflowService.StartResult.Started ->
                buildJsonObject {
                    put("workflow_run_id", r.workflowRunId)
                    if (r.extractAudioTaskId != null) put("extract_audio_task_id", r.extractAudioTaskId)
                    if (r.transcribeTaskId != null) put("transcribe_task_id", r.transcribeTaskId)
                }.toValidJson()
        }
    }
}
