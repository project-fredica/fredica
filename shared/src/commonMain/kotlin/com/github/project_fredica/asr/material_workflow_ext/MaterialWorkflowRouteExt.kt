package com.github.project_fredica.asr.material_workflow_ext

import com.github.project_fredica.api.routes.MaterialWorkflowParam
import com.github.project_fredica.api.routes.MaterialWorkflowRoute
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.db.MaterialVideo
import com.github.project_fredica.db.MaterialWorkflowService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * whisper_transcribe 模板的路由处理：解析参数 → 启动 ASR 工作流 → 返回 JSON。
 */
@Suppress("UnusedReceiverParameter")
suspend fun MaterialWorkflowRoute.handleWhisperTranscribe(
    p: MaterialWorkflowParam,
    material: MaterialVideo,
): ValidJsonString {
    if (p.model.isNullOrBlank()) return buildJsonObject { put("error", "MODEL_REQUIRED") }.toValidJson()
    return when (val r = MaterialWorkflowService.startWhisperTranscribe2(material, model = p.model, language = p.language, allowDownload = p.allowDownload)) {
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
