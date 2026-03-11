package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskStatusService
import com.github.project_fredica.db.WorkflowRun
import com.github.project_fredica.db.WorkflowRunStatusService
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 通过 kmpJsBridge 创建 DOWNLOAD_WHISPER_MODEL 任务。
 *
 * JS 调用方式：
 * ```js
 * kmpJsBridge.callNative('run_whisper_model_download',
 *     JSON.stringify({ model_name: "large-v3" }),
 *     (result) => {
 *         const r = JSON.parse(result);
 *         // r.task_id          — 任务 ID
 *         // r.workflow_run_id  — 工作流 ID
 *         // r.error            — 错误码（MISSING_MODEL_NAME / TASK_ALREADY_ACTIVE）
 *     }
 * );
 * ```
 */
class RunFasterWhisperModelDownloadJsMessageHandler : MyJsMessageHandler() {

    @Serializable
    private data class Param(
        @SerialName("model_name") val modelName: String = "",
    )

    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        val param = runCatching { Json.decodeFromString<Param>(message.params) }.getOrDefault(Param())
        if (param.modelName.isBlank()) {
            callback(buildValidJson { kv("error", "MISSING_MODEL_NAME") }.str)
            return
        }

        val activeStatuses = setOf("pending", "claimed", "running")
        val idempotencyKey = "DOWNLOAD_WHISPER_MODEL:${param.modelName}"
        val isActive = TaskStatusService.listAll(pageSize = 200)
            .items.any { it.idempotencyKey == idempotencyKey && it.status in activeStatuses }
        if (isActive) {
            callback(buildValidJson { kv("error", "TASK_ALREADY_ACTIVE") }.str)
            return
        }

        val cfg = AppConfigService.repo.getConfig()
        val nowSec = System.currentTimeMillis() / 1000L
        val workflowRunId = UUID.randomUUID().toString()
        val taskId = UUID.randomUUID().toString()

        val payload = buildValidJson {
            kv("model_name", param.modelName)
            kv("proxy", cfg.proxyUrl)
            if (cfg.fasterWhisperModelsDir.isNotBlank()) kv("models_dir", cfg.fasterWhisperModelsDir)
        }.str

        WorkflowRunStatusService.create(
            WorkflowRun(
                id         = workflowRunId,
                materialId = "",
                template   = "asr_download_model",
                status     = "pending",
                totalTasks = 1,
                doneTasks  = 0,
                createdAt  = nowSec,
            )
        )
        TaskStatusService.create(
            Task(
                id             = taskId,
                type           = "DOWNLOAD_WHISPER_MODEL",
                workflowRunId  = workflowRunId,
                materialId     = "",
                payload        = payload,
                idempotencyKey = idempotencyKey,
                createdAt      = nowSec,
            )
        )

        callback(buildValidJson {
            kv("task_id", taskId)
            kv("workflow_run_id", workflowRunId)
        }.str)
    }
}
