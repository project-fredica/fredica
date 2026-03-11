package com.github.project_fredica.worker.executors

import com.github.project_fredica.apputil.buildValidJson
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

/**
 * 评估本地 GPU 对 Whisper 各 compute_type / 模型的兼容性。
 *
 * ## Payload 格式
 * ```json
 * { "proxy": "" }
 * ```
 *
 * ## 执行流程（5 Step，由 Python 侧完成）
 * Step 1 — 扫描本地已有模型
 * Step 2 — 获取设备信息
 * Step 3 — 用 tiny 模型测试各 compute_type 支持性
 * Step 4 — 检查下载可达性
 * Step 5 — 逐级加载模型，得出支持表
 *
 * ## 结果写入
 * Python 推送 done 消息后，将兼容性 JSON 写入 AppConfig.fasterWhisperCompatJson。
 */
object EvaluateWhisperCompatExecutor : WebSocketTaskExecutor() {
    override val taskType = "EVALUATE_WHISPER_COMPAT"
    private val logger = createLogger()

    @Serializable
    private data class Payload(
        val proxy: String = "",
    )

    override suspend fun canSkip(task: Task) = false

    override suspend fun executeWithSignals(
        task: Task,
        cancelSignal: CompletableDeferred<Unit>,
        pauseResumeChannels: TaskPauseResumeChannels,
    ): ExecuteResult = withContext(Dispatchers.IO) {
        val payload = runCatching { Json.decodeFromString<Payload>(task.payload) }
            .getOrDefault(Payload())

        val cfg = AppConfigService.repo.getConfig()
        val paramJson = buildValidJson {
            kv("proxy", payload.proxy.ifBlank { cfg.proxyUrl })
            if (cfg.fasterWhisperModelsDir.isNotBlank()) kv("models_dir", cfg.fasterWhisperModelsDir)
        }.str

        logger.info("EvaluateWhisperCompatExecutor: 开始兼容性评估 [taskId=${task.id}]")

        val result = PythonUtil.Py314Embed.PyUtilServer.websocketTask(
            pth = "/asr/evaluate-compat-task",
            paramJson = paramJson,
            onProgress = { pct -> TaskService.repo.updateProgress(task.id, pct) },
            cancelSignal = cancelSignal,
            pauseChannel = pauseResumeChannels.pause,
            resumeChannel = pauseResumeChannels.resume,
        ) ?: return@withContext ExecuteResult(error = "用户已取消", errorType = "CANCELLED")

        // 将兼容性结果写入 AppConfig
        runCatching {
            val updated = AppConfigService.repo.getConfig().copy(fasterWhisperCompatJson = result)
            AppConfigService.repo.updateConfig(updated)
        }.onFailure { logger.warn("EvaluateWhisperCompatExecutor: 写入 fasterWhisperCompatJson 失败: ${it.message}") }

        logger.info("EvaluateWhisperCompatExecutor: 评估完成 [taskId=${task.id}]")
        ExecuteResult(result = result)
    }
}
