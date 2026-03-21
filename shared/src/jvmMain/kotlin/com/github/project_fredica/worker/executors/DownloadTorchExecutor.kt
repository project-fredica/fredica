package com.github.project_fredica.worker.executors

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJsonModel
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 下载指定 variant 的 torch 到隔离目录。
 *
 * 从 task.payload 读取下载参数（由 DownloadTorchJsMessageHandler 写入），
 * 直接构造 paramJson 传给 Python install_torch_worker，不依赖 AppConfigService。
 *
 * 完成后将 torchVariant 写入 AppConfig 持久化。
 */
object DownloadTorchExecutor : WebSocketTaskExecutor() {
    override val taskType = "DOWNLOAD_TORCH"
    private val logger = createLogger()

    @Serializable
    private data class Payload(
        @SerialName("variant")           val variant: String = "",
        @SerialName("torch_version")     val torchVersion: String = "",
        @SerialName("index_url")         val indexUrl: String = "",
        @SerialName("index_url_mode")    val indexUrlMode: String = "replace",
        @SerialName("use_proxy")         val useProxy: Boolean = false,
        @SerialName("proxy")             val proxy: String = "",
        @SerialName("expected_version")  val expectedVersion: String = "",
        @SerialName("custom_packages")   val customPackages: String = "",
        @SerialName("custom_index_url")  val customIndexUrl: String = "",
        @SerialName("custom_variant_id") val customVariantId: String = "",
    )

    override suspend fun canSkip(task: Task): Boolean = false

    override suspend fun executeWithSignals(
        task: Task,
        cancelSignal: CompletableDeferred<Unit>,
        pauseResumeChannels: TaskPauseResumeChannels,
    ): ExecuteResult = withContext(Dispatchers.IO) {
        val payload = task.payload.loadJsonModel<Payload>().getOrElse { Payload() }
        val variant = payload.variant
        if (variant.isEmpty()) {
            logger.warn("DownloadTorchExecutor: variant 为空 [taskId=${task.id}]")
            return@withContext ExecuteResult(error = "variant 未指定", errorType = "MISSING_TORCH_VARIANT")
        }

        val downloadDir = AppUtil.Paths.pipLibDir.absolutePath
        val pipLogFilePath = AppUtil.Paths.appDataLogDir
            .resolve("install_torch_worker")
            .resolve("pip.stdout.log")
            .absolutePath
        logger.info("DownloadTorchExecutor: 开始下载 variant=$variant " +
            "torchVersion=${payload.torchVersion} indexUrl=${payload.indexUrl} " +
            "indexUrlMode=${payload.indexUrlMode} downloadDir=$downloadDir pipLogFilePath=$pipLogFilePath [taskId=${task.id}]")

        val paramJson = buildValidJson {
            kv("variant", variant)
            kv("download_dir", downloadDir)
            kv("use_proxy", payload.useProxy)
            kv("proxy", payload.proxy)
            kv("index_url_mode", payload.indexUrlMode)
            kv("pip_log_file_path", pipLogFilePath)
            if (payload.expectedVersion.isNotBlank()) kv("expected_version", payload.expectedVersion)
            if (variant == "custom") {
                kv("custom_packages", payload.customPackages)
                kv("custom_index_url", payload.customIndexUrl)
                kv("custom_variant_id", payload.customVariantId)
            } else {
                kv("torch_version", payload.torchVersion)
                kv("index_url", payload.indexUrl)
            }
        }.str
        logger.debug("DownloadTorchExecutor: paramJson (proxy masked)=${
            paramJson.replace(Regex("\"proxy\":\"[^\"]+\""), "\"proxy\":\"***\"")
        } [taskId=${task.id}]")

        val result = PythonUtil.Py314Embed.PyUtilServer.websocketTask(
            pth = "/torch/install-task",
            paramJson = paramJson,
            onProgress = { pct ->
                TaskService.repo.updateProgress(task.id, pct)
            },
            onProgressLine = { line ->
                TaskService.repo.updateStatusText(task.id, line)
            },
            onPausable = { pausable ->
                logger.debug("DownloadTorchExecutor: updatePausable pausable=$pausable [taskId=${task.id}]")
                TaskService.repo.updatePausable(task.id, pausable)
            },
            cancelSignal = cancelSignal,
            pauseChannel = pauseResumeChannels.pause,
            resumeChannel = pauseResumeChannels.resume,
        ) ?: return@withContext ExecuteResult(error = "用户已取消", errorType = "CANCELLED")

        // 下载完成，写入 torchVariant 持久化
        val actualVariant = if (variant == "custom") payload.customVariantId.ifBlank { "custom" } else variant
        AppConfigService.repo.updateConfig(
            AppConfigService.repo.getConfig().copy(torchVariant = actualVariant)
        )

        logger.info("DownloadTorchExecutor: 下载完成 variant=$actualVariant [taskId=${task.id}]")
        ExecuteResult(result = result)
    }
}
