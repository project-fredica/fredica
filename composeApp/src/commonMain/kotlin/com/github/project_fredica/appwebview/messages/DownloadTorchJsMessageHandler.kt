package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskStatusService
import com.github.project_fredica.db.WorkflowRun
import com.github.project_fredica.db.WorkflowRunStatusService
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import java.util.UUID

/**
 * 通过 kmpJsBridge 创建 DOWNLOAD_TORCH 任务。
 *
 * 若 AppConfig.torchVariant 已有值，任务直接以该 variant 启动；
 * 若为空，任务启动后会自动暂停并标记 AWAITING_TORCH_VARIANT，
 * 前端需先调用 save_torch_config 写入 variant，再 resume 任务。
 *
 * JS 调用方式：
 * ```js
 * kmpJsBridge.callNative('download_torch', '{}', (result) => {
 *     const r = JSON.parse(result);
 *     // r.task_id          — 任务 ID
 *     // r.workflow_run_id  — 工作流 ID
 *     // r.error            — 错误码（TASK_ALREADY_ACTIVE）
 * });
 * ```
 */
class DownloadTorchJsMessageHandler : MyJsMessageHandler() {
    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        val activeStatuses = setOf("pending", "claimed", "running")
        val isActive = TaskStatusService.listAll(pageSize = 200)
            .items.any { it.type == "DOWNLOAD_TORCH" && it.status in activeStatuses }
        if (isActive) {
            callback(buildValidJson { kv("error", "TASK_ALREADY_ACTIVE") }.str)
            return
        }

        val nowSec = System.currentTimeMillis() / 1000L
        val workflowRunId = UUID.randomUUID().toString()
        val taskId = UUID.randomUUID().toString()

        WorkflowRunStatusService.create(
            WorkflowRun(
                id         = workflowRunId,
                materialId = "",
                template   = "torch_download",
                status     = "pending",
                totalTasks = 1,
                doneTasks  = 0,
                createdAt  = nowSec,
            )
        )
        TaskStatusService.create(
            Task(
                id            = taskId,
                type          = "DOWNLOAD_TORCH",
                workflowRunId = workflowRunId,
                materialId    = "",
                payload       = "{}",
                createdAt     = nowSec,
            )
        )

        callback(buildValidJson {
            kv("task_id", taskId)
            kv("workflow_run_id", workflowRunId)
        }.str)
    }
}
