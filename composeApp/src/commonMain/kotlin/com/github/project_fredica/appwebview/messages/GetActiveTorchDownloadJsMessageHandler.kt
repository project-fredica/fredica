package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.db.TaskService
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator

/**
 * 查询当前活跃的 DOWNLOAD_TORCH 任务，返回其 workflow_run_id。
 *
 * 用于前端 mount 时恢复下载进度面板（页面刷新后 state 丢失场景）。
 *
 * JS 调用方式：
 * ```js
 * const raw = await callBridgeOrNull('get_active_torch_download');
 * if (raw) {
 *     const res = JSON.parse(raw);
 *     if (res.workflow_run_id) {
 *         setDownloadWorkflowRunId(res.workflow_run_id);
 *         setDownloading(true);
 *     }
 * }
 * ```
 *
 * 响应：{ "workflow_run_id": str, "task_id": str, "status": str }
 * 无活跃任务时三个字段均为空串。
 */
class GetActiveTorchDownloadJsMessageHandler : MyJsMessageHandler() {
    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        val activeStatuses = setOf("pending", "claimed", "running")
        val task = TaskService.repo.listByType("DOWNLOAD_TORCH")
            .firstOrNull { it.status in activeStatuses }
        callback(buildValidJson {
            kv("workflow_run_id", task?.workflowRunId ?: "")
            kv("task_id", task?.id ?: "")
            kv("status", task?.status ?: "")
        }.str)
    }
}
