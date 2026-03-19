package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskService
import com.github.project_fredica.db.WorkflowRun
import com.github.project_fredica.db.WorkflowRunStatusService
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 通过 kmpJsBridge 创建 DOWNLOAD_TORCH 任务。
 *
 * 下载参数由前端直接传入（不依赖 AppConfig），写入 Task payload，
 * 由 DownloadTorchExecutor 读取后透传给 Python install_torch_worker。
 *
 * JS 调用方式：
 * ```js
 * kmpJsBridge.callNative('download_torch', JSON.stringify({
 *     variant: 'cu124',
 *     torch_version: '2.7.0',   // 可选，空串时安装最新版
 *     index_url: 'https://download.pytorch.org/whl/cu124',
 *     index_url_mode: 'replace',
 *     use_proxy: false,
 *     proxy: '',
 * }), (result) => {
 *     const r = JSON.parse(result);
 *     // r.task_id          — 任务 ID
 *     // r.workflow_run_id  — 工作流 ID
 *     // r.error            — 错误码（TASK_ALREADY_ACTIVE）
 *     // r.workflow_run_id  — 已有任务的 workflow_run_id（TASK_ALREADY_ACTIVE 时附带）
 * });
 * ```
 */
class DownloadTorchJsMessageHandler : MyJsMessageHandler() {

    @Serializable
    data class Param(
        @SerialName("variant")           val variant: String = "",
        @SerialName("torch_version")     val torchVersion: String = "",
        @SerialName("index_url")         val indexUrl: String = "",
        @SerialName("index_url_mode")    val indexUrlMode: String = "replace",
        @SerialName("use_proxy")         val useProxy: Boolean = false,
        @SerialName("proxy")             val proxy: String = "",
        @SerialName("expected_version")  val expectedVersion: String = "",
        // custom variant 专用
        @SerialName("custom_packages")   val customPackages: String = "",
        @SerialName("custom_index_url")  val customIndexUrl: String = "",
        @SerialName("custom_variant_id") val customVariantId: String = "",
    )

    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        val param = message.params.loadJsonModel<Param>().getOrElse { Param() }
        logger.info("DownloadTorchJsMessageHandler: variant=${param.variant} " +
            "torchVersion=${param.torchVersion} indexUrl=${param.indexUrl} " +
            "indexUrlMode=${param.indexUrlMode} useProxy=${param.useProxy}")

        val activeStatuses = setOf("pending", "claimed", "running")
        val activeTask = TaskService.repo.listByType("DOWNLOAD_TORCH")
            .firstOrNull { it.status in activeStatuses }
        if (activeTask != null) {
            callback(buildValidJson {
                kv("error", "TASK_ALREADY_ACTIVE")
                kv("workflow_run_id", activeTask.workflowRunId)
            }.str)
            return
        }

        val payload = AppUtil.dumpJsonStr(param).getOrThrow().str
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
        TaskService.repo.create(
            Task(
                id            = taskId,
                type          = "DOWNLOAD_TORCH",
                workflowRunId = workflowRunId,
                materialId    = "",
                payload       = payload,
                createdAt     = nowSec,
            )
        )

        callback(buildValidJson {
            kv("task_id", taskId)
            kv("workflow_run_id", workflowRunId)
        }.str)
    }
}
