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
 * 提取视频音频并按时长切段（约 5 分钟/段）。
 *
 * ## Payload 格式
 * ```json
 * {
 *   "input_video_path": "/path/to/video.m4s",
 *   "output_dir":       "/path/to/audio_chunks/",
 *   "chunk_duration_sec": 300
 * }
 * ```
 *
 * ## 跳过机制（canSkip）
 * 检测 output_dir 下是否存在 `extract_audio.done`。
 */
object ExtractAudioExecutor : WebSocketTaskExecutor() {
    override val taskType = "EXTRACT_AUDIO"
    private val logger = createLogger()

    @Serializable
    private data class Payload(
        @SerialName("input_video_path")   val inputVideoPath: String,
        @SerialName("output_dir")         val outputDir: String,
        @SerialName("chunk_duration_sec") val chunkDurationSec: Int = 300,
    )

    override suspend fun canSkip(task: Task): Boolean {
        val payload = runCatching { Json.decodeFromString<Payload>(task.payload) }.getOrNull() ?: return false
        val done = File(payload.outputDir).resolve("extract_audio.done").exists()
        logger.debug("ExtractAudioExecutor.canSkip: outputDir=${payload.outputDir} done=$done")
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
            logger.error("ExtractAudioExecutor: payload 解析失败，taskId=${task.id}: ${e.message}")
            return@withContext ExecuteResult(error = "Invalid payload: ${e.message}", errorType = "PAYLOAD_ERROR")
        }

        File(payload.outputDir).mkdirs()

        val paramJson = buildValidJson {
            kv("video_path", payload.inputVideoPath)
            kv("output_dir", payload.outputDir)
            kv("chunk_duration_sec", payload.chunkDurationSec)
        }.str

        logger.info("ExtractAudioExecutor: 开始提取音频 input=${payload.inputVideoPath} [taskId=${task.id}]")

        return@withContext try {
            val result = PythonUtil.Py314Embed.PyUtilServer.websocketTask(
                pth = "/audio/extract-split-audio-task",
                paramJson = paramJson,
                onProgress = { pct -> TaskService.repo.updateProgress(task.id, pct) },
                cancelSignal = cancelSignal,
                pauseChannel = pauseResumeChannels.pause,
                resumeChannel = pauseResumeChannels.resume,
            )
            if (result == null) {
                logger.info("ExtractAudioExecutor: 已取消 [taskId=${task.id}]")
                ExecuteResult(error = "用户已取消", errorType = "CANCELLED")
            } else {
                File(payload.outputDir).resolve("extract_audio.done").writeText("ok")
                logger.info("ExtractAudioExecutor: 完成 [taskId=${task.id}]")
                ExecuteResult(result = result)
            }
        } catch (e: Throwable) {
            if (cancelSignal.isCompleted) {
                ExecuteResult(error = "用户已取消", errorType = "CANCELLED")
            } else {
                logger.error("ExtractAudioExecutor: 失败 [taskId=${task.id}]: ${e.message}")
                ExecuteResult(error = "Extract audio failed: ${e.message}", errorType = "EXTRACT_ERROR")
            }
        }
    }
}
