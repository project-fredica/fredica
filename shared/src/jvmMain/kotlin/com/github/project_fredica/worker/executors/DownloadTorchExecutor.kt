package com.github.project_fredica.worker.executors

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskService
import com.github.project_fredica.python.PythonUtil
import com.github.project_fredica.worker.ExecuteResult
import com.github.project_fredica.worker.TaskPauseResumeChannels
import com.github.project_fredica.worker.TaskPauseResumeService
import com.github.project_fredica.worker.WebSocketTaskExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 下载指定 variant 的 torch 到隔离目录。
 *
 * ## 暂停等待逻辑
 * 若 AppConfig.torchVariant 为空，任务标记 AWAITING_TORCH_VARIANT 并暂停，
 * 循环等待用户在设置页选择版本后手动恢复，每次恢复后重新读取 torchVariant。
 * variant 非空后清除 errorType，以确定的 variant 启动 websocketTask。
 *
 * ## 完成后
 * 将 torchVariant 写入 AppConfig（确认），提示重启 Python 服务。
 */
object DownloadTorchExecutor : WebSocketTaskExecutor() {
    override val taskType = "DOWNLOAD_TORCH"
    private val logger = createLogger()

    override suspend fun canSkip(task: Task): Boolean = false

    override suspend fun executeWithSignals(
        task: Task,
        cancelSignal: CompletableDeferred<Unit>,
        pauseResumeChannels: TaskPauseResumeChannels,
    ): ExecuteResult = withContext(Dispatchers.IO) {
        // 等待 torchVariant 非空
        var variant = AppConfigService.repo.getConfig().torchVariant
        if (variant.isEmpty()) {
            logger.info("DownloadTorchExecutor: torchVariant 为空，暂停等待用户选择 [taskId=${task.id}]")
            TaskService.repo.updateStatus(task.id, "running", errorType = "AWAITING_TORCH_VARIANT")
            TaskPauseResumeService.pause(task.id)

            while (variant.isEmpty()) {
                pauseResumeChannels.resume.receive()
                if (cancelSignal.isCompleted) {
                    return@withContext ExecuteResult(error = "用户已取消", errorType = "CANCELLED")
                }
                variant = AppConfigService.repo.getConfig().torchVariant
                if (variant.isEmpty()) {
                    logger.info("DownloadTorchExecutor: 恢复后 torchVariant 仍为空，再次暂停 [taskId=${task.id}]")
                    TaskPauseResumeService.pause(task.id)
                }
            }
            // variant 已就绪，清除等待状态
            TaskService.repo.updateStatus(task.id, "running", errorType = null)
            logger.info("DownloadTorchExecutor: torchVariant 已就绪 variant=$variant [taskId=${task.id}]")
        }

        val cfg = AppConfigService.repo.getConfig()
        val downloadDir = AppUtil.Paths.torchDownloadDir.absolutePath
        val paramJson = buildValidJson {
            kv("variant", variant)
            kv("download_dir", downloadDir)
            kv("use_proxy", cfg.torchDownloadUseProxy)
            kv("proxy", cfg.torchDownloadProxyUrl)
            if (variant == "custom") {
                kv("custom_packages", cfg.torchCustomPackages)
                kv("custom_index_url", cfg.torchCustomIndexUrl)
                kv("custom_variant_id", cfg.torchCustomVariantId)
            } else if (cfg.torchDownloadIndexUrl.isNotBlank()) {
                // 内置 variant 的自定义下载源（如国内镜像），覆盖默认官方源
                kv("index_url", cfg.torchDownloadIndexUrl)
            }
        }.str

        logger.info("DownloadTorchExecutor: 开始下载 variant=$variant [taskId=${task.id}]")

        val result = PythonUtil.Py314Embed.PyUtilServer.websocketTask(
            pth = "/torch/install-task",
            paramJson = paramJson,
            onProgress = { pct -> TaskService.repo.updateProgress(task.id, pct) },
            cancelSignal = cancelSignal,
            pauseChannel = pauseResumeChannels.pause,
            resumeChannel = pauseResumeChannels.resume,
        ) ?: return@withContext ExecuteResult(error = "用户已取消", errorType = "CANCELLED")

        // 下载完成，写入 torchVariant 确认
        val actualVariant = if (variant == "custom") cfg.torchCustomVariantId.ifBlank { "custom" } else variant
        AppConfigService.repo.updateConfig(
            AppConfigService.repo.getConfig().copy(torchVariant = actualVariant)
        )

        logger.info("DownloadTorchExecutor: 下载完成 variant=$actualVariant [taskId=${task.id}]")
        ExecuteResult(result = result)
    }
}
