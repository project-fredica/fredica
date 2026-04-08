package com.github.project_fredica.worker.executors

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskService
import com.github.project_fredica.python.PythonUtil
import com.github.project_fredica.worker.ExecuteResult
import com.github.project_fredica.worker.TaskPauseResumeChannels
import com.github.project_fredica.worker.WebSocketTaskExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 下载指定 Whisper 模型到本地缓存目录。
 *
 * ## Payload 格式
 * ```json
 * {
 *   "model_name": "large-v3",
 *   "proxy":      ""           // 可选，HTTP 代理
 * }
 * ```
 *
 * ## 幂等键
 * `DOWNLOAD_WHISPER_MODEL:{model_name}`
 *
 * ## 跳过机制（canSkip）
 * 检查本地 HuggingFace 缓存目录中是否已存在该模型。
 */
object DownloadWhisperModelExecutor : WebSocketTaskExecutor() {
    override val taskType = "DOWNLOAD_WHISPER_MODEL"
    private val logger = createLogger()

    @Serializable
    private data class Payload(
        @SerialName("model_name") val modelName: String,
        val proxy: String = "",
    )

    override suspend fun canSkip(task: Task): Boolean {
        val payload = runCatching { Json.decodeFromString<Payload>(task.payload) }.getOrNull() ?: return false
        val modelName = payload.modelName
        // 按 HuggingFace 优先级解析缓存目录：HF_HOME > XDG_CACHE_HOME > ~/.cache
        val hfCacheDir = run {
            val hfHome = System.getenv("HF_HOME")
            if (!hfHome.isNullOrBlank()) return@run java.io.File(hfHome, "hub")
            val xdgCache = System.getenv("XDG_CACHE_HOME")
            if (!xdgCache.isNullOrBlank()) return@run java.io.File(xdgCache, "huggingface/hub")
            java.io.File(System.getProperty("user.home"), ".cache/huggingface/hub")
        }
        val exists = hfCacheDir.exists() && hfCacheDir.listFiles()?.any {
            it.name.contains("faster-whisper-$modelName")
        } == true
        logger.debug("DownloadWhisperModelExecutor.canSkip: model=$modelName hfCacheDir=${hfCacheDir.absolutePath} exists=$exists")
        return exists
    }

    override suspend fun executeWithSignals(
        task: Task,
        cancelSignal: CompletableDeferred<Unit>,
        pauseResumeChannels: TaskPauseResumeChannels,
    ): ExecuteResult = withContext(Dispatchers.IO) {
        val payload = try {
            Json.decodeFromString<Payload>(task.payload)
        } catch (e: Throwable) {
            return@withContext ExecuteResult(error = "Invalid payload: ${e.message}", errorType = "PAYLOAD_ERROR")
        }

        val cfg = AppConfigService.repo.getConfig()
        val paramJson = buildJsonObject {
            put("model_name", payload.modelName)
            put("proxy", payload.proxy.ifBlank { cfg.proxyUrl })
            if (cfg.fasterWhisperModelsDir.isNotBlank()) put("models_dir", cfg.fasterWhisperModelsDir)
        }.toString()

        logger.info("DownloadWhisperModelExecutor: 开始下载 model=${payload.modelName} [taskId=${task.id}]")

        val result = PythonUtil.Py314Embed.PyUtilServer.websocketTask(
            pth = "/asr/download-model-task",
            paramJson = paramJson,
            onProgress = { pct -> TaskService.repo.updateProgress(task.id, pct) },
            cancelSignal = cancelSignal,
            pauseChannel = pauseResumeChannels.pause,
            resumeChannel = pauseResumeChannels.resume,
        ) ?: return@withContext ExecuteResult(error = "用户已取消", errorType = "CANCELLED")

        logger.info("DownloadWhisperModelExecutor: 下载完成 model=${payload.modelName} [taskId=${task.id}]")
        ExecuteResult(result = result)
    }
}
