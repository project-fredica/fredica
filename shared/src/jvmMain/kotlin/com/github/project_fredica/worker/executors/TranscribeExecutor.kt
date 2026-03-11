package com.github.project_fredica.worker.executors

import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
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
import java.io.File

/**
 * Whisper 语音识别（单段音频文件）。
 *
 * ## Payload 格式
 * ```json
 * {
 *   "audio_path":  "/path/to/chunk.m4a",
 *   "language":    "zh",         // 可选，null 为自动检测
 *   "model_size":  "large-v3",   // 可选
 *   "output_path": "/path/to/chunk_0000.json"  // 结果写入路径
 * }
 * ```
 *
 * ## 跳过机制（canSkip）
 * 检测 output_path 是否已存在，或同目录下 `transcribe.done` 是否存在。
 */
object TranscribeExecutor : WebSocketTaskExecutor() {
    override val taskType = "TRANSCRIBE"
    private val logger = createLogger()

    @Serializable
    private data class Payload(
        @SerialName("audio_path")   val audioPath: String,
        val language: String? = null,
        @SerialName("model_size")   val modelSize: String? = null,
        @SerialName("output_path")  val outputPath: String? = null,
    )

    override suspend fun canSkip(task: Task): Boolean {
        val payload = runCatching { Json.decodeFromString<Payload>(task.payload) }.getOrNull() ?: return false
        val outputPath = payload.outputPath ?: return false
        val done = File(outputPath).exists()
        logger.debug("TranscribeExecutor.canSkip: outputPath=$outputPath exists=$done")
        return done
    }

    override suspend fun executeWithSignals(
        task: Task,
        cancelSignal: CompletableDeferred<Unit>,
        pauseResumeChannels: TaskPauseResumeChannels,
    ): ExecuteResult = withContext(Dispatchers.IO) {
        val payload = try {
            Json.decodeFromString<Payload>(task.payload)
        } catch (e: Throwable) {
            logger.error("TranscribeExecutor: payload 解析失败，taskId=${task.id}: ${e.message}")
            return@withContext ExecuteResult(error = "Invalid payload: ${e.message}", errorType = "PAYLOAD_ERROR")
        }

        val paramJson = buildValidJson {
            kv("audio_path", payload.audioPath)
            if (payload.language != null) kv("language", payload.language)
            if (payload.modelSize != null) kv("model_name", payload.modelSize)
            kv("device", "auto")
        }.str

        logger.info("TranscribeExecutor: 开始转录 audio=${payload.audioPath} [taskId=${task.id}]")

        return@withContext try {
            val result = PythonUtil.Py314Embed.PyUtilServer.websocketTask(
                pth = "/audio/transcribe-chunk-task",
                paramJson = paramJson,
                onProgress = { pct -> TaskService.repo.updateProgress(task.id, pct) },
                cancelSignal = cancelSignal,
                pauseChannel = pauseResumeChannels.pause,
                resumeChannel = pauseResumeChannels.resume,
            )
            if (result == null) {
                logger.info("TranscribeExecutor: 已取消 [taskId=${task.id}]")
                ExecuteResult(error = "用户已取消", errorType = "CANCELLED")
            } else {
                // 写入 output_path（如果指定）
                payload.outputPath?.let { outPath ->
                    File(outPath).parentFile?.mkdirs()
                    File(outPath).writeText(result)
                }
                logger.info("TranscribeExecutor: 完成 [taskId=${task.id}]")
                ExecuteResult(result = result)
            }
        } catch (e: Throwable) {
            if (cancelSignal.isCompleted) {
                ExecuteResult(error = "用户已取消", errorType = "CANCELLED")
            } else {
                logger.error("TranscribeExecutor: 失败 [taskId=${task.id}]: ${e.message}")
                ExecuteResult(error = "Transcribe failed: ${e.message}", errorType = "TRANSCRIBE_ERROR")
            }
        }
    }
}
