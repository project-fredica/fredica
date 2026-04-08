package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.db.AppConfigService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskStatusService
import com.github.project_fredica.db.WorkflowRun
import com.github.project_fredica.db.WorkflowRunStatusService
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import java.util.UUID

/**
 * 通过 kmpJsBridge 创建 EVALUATE_WHISPER_COMPAT 任务。
 *
 * JS 调用方式：
 * ```js
 * kmpJsBridge.callNative('run_whisper_compat_eval', '{}', (result) => {
 *     const r = JSON.parse(result);
 *     // r.task_id          — 任务 ID，可通过 WorkerTaskListRoute 轮询进度
 *     // r.workflow_run_id  — 工作流 ID
 *     // r.error            — 错误码（如 TASK_ALREADY_ACTIVE）
 * });
 * ```
 */
class RunFasterWhisperCompatEvalJsMessageHandler : MyJsMessageHandler() {
    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        val activeStatuses = setOf("pending", "claimed", "running")
        val isActive = TaskStatusService.listAll(pageSize = 200)
            .items.any { it.type == "EVALUATE_WHISPER_COMPAT" && it.status in activeStatuses }
        if (isActive) {
            callback(buildJsonObject { put("error", "TASK_ALREADY_ACTIVE") }.toString())
            return
        }

        val cfg = AppConfigService.repo.getConfig()
        val nowSec = System.currentTimeMillis() / 1000L
        val workflowRunId = UUID.randomUUID().toString()
        val taskId = UUID.randomUUID().toString()

        val payload = buildJsonObject {
            put("proxy", cfg.proxyUrl)
            if (cfg.fasterWhisperModelsDir.isNotBlank()) put("models_dir", cfg.fasterWhisperModelsDir)
        }.toString()

        WorkflowRunStatusService.create(
            WorkflowRun(
                id         = workflowRunId,
                materialId = "",
                template   = "asr_eval",
                status     = "pending",
                totalTasks = 1,
                doneTasks  = 0,
                createdAt  = nowSec,
            )
        )
        TaskStatusService.create(
            Task(
                id            = taskId,
                type          = "EVALUATE_WHISPER_COMPAT",
                workflowRunId = workflowRunId,
                materialId    = "",
                payload       = payload,
                createdAt     = nowSec,
            )
        )

        callback(buildJsonObject {
            put("task_id", taskId)
            put("workflow_run_id", workflowRunId)
        }.toString())
    }
}
