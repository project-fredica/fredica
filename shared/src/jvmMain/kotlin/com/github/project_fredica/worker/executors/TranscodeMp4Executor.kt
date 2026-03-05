package com.github.project_fredica.worker.executors

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.json
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskService
import com.github.project_fredica.python.PythonUtil
import com.github.project_fredica.worker.ExecuteResult
import com.github.project_fredica.worker.TaskCancelService
import com.github.project_fredica.worker.TaskExecutor
import com.github.project_fredica.worker.TaskPauseResumeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Transcodes downloaded Bilibili video files (.m4s/.flv) to .mp4 via the Python
 * FFmpeg transcoding WebSocket endpoint.
 *
 * Payload:
 * ```json
 * {
 *   "input_video":  "/data/media/BV1xxx/video.m4s",
 *   "input_audio":  "/data/media/BV1xxx/audio.m4s",   // null for FLV
 *   "output_path":  "/data/media/BV1xxx/video.mp4",
 *   "hw_accel":     "auto"                            // optional, default "auto"
 * }
 * ```
 *
 * The executor resolves `hw_accel = "auto"` by reading `selected_accel` from
 * `AppConfig.ffmpegProbeJson` before passing it to Python.
 */
object TranscodeMp4Executor : TaskExecutor {
    override val taskType = "TRANSCODE_MP4"
    private val logger = createLogger()

    @Serializable
    private data class Payload(
        @SerialName("input_video") val inputVideo: String,
        @SerialName("input_audio") val inputAudio: String? = null,
        @SerialName("output_path") val outputPath: String,
        @SerialName("hw_accel") val hwAccel: String = "auto",
    )

    override suspend fun execute(task: Task): ExecuteResult = withContext(Dispatchers.IO) {
        val payload = try {
            Json.decodeFromString<Payload>(task.payload)
        } catch (e: Throwable) {
            return@withContext ExecuteResult(error = "Invalid payload: ${e.message}", errorType = "PAYLOAD_ERROR")
        }

        // Resolve hw_accel: if "auto", read selected_accel from probe info
        val config = AppConfigService.repo.getConfig()
        val resolvedAccel = if (payload.hwAccel == "auto") {
            resolveSelectedAccel(config.ffmpegHwAccel, config.ffmpegProbeJson)
        } else {
            payload.hwAccel
        }

        // Determine ffmpeg_path: use configured path if set, else rely on Python to find it
        val ffmpegPath = if (config.ffmpegPath.isNotBlank()) config.ffmpegPath else resolveProbedFfmpegPath(config.ffmpegProbeJson)

        logger.info("TranscodeMp4Executor: ${payload.inputVideo} → ${payload.outputPath} [accel=$resolvedAccel, ffmpeg=$ffmpegPath]")

        val paramJson = buildValidJson {
            kv("input_video", payload.inputVideo)
            kv("output_path", payload.outputPath)
            kv("hw_accel", resolvedAccel)
            kv("ffmpeg_path", ffmpegPath)
            if (payload.inputAudio != null) {
                kv("input_audio", payload.inputAudio)
            }
        }.str

        val cancelSignal = TaskCancelService.register(task.id)
        val pauseResumeChannels = TaskPauseResumeService.register(task.id)
        return@withContext try {
            val result = PythonUtil.Py314Embed.PyUtilServer.websocketTask(
                pth = "/transcode/mp4-task",
                paramJson = paramJson,
                onProgress = { pct -> TaskService.repo.updateProgress(task.id, pct) },
                cancelSignal = cancelSignal,
                pauseChannel = pauseResumeChannels.pause,
                resumeChannel = pauseResumeChannels.resume,
            )
            if (result == null) {
                ExecuteResult(error = "用户已取消转码", errorType = "CANCELLED")
            } else {
                ExecuteResult(result = result)
            }
        } catch (e: Throwable) {
            if (cancelSignal.isCompleted) {
                ExecuteResult(error = "用户已取消转码", errorType = "CANCELLED")
            } else {
                ExecuteResult(error = "Transcode failed: ${e.message}", errorType = "TRANSCODE_ERROR")
            }
        } finally {
            TaskCancelService.unregister(task.id)
            TaskPauseResumeService.unregister(task.id)
            @Suppress("SwallowedException")
            try { TaskService.repo.updatePaused(task.id, false) } catch (_: Exception) {}
        }
    }

    private fun resolveSelectedAccel(configuredAccel: String, ffmpegProbeJson: String): String {
        if (configuredAccel != "auto") return configuredAccel
        if (ffmpegProbeJson.isBlank()) return "cpu"
        return try {
            val json = AppUtil.GlobalVars.json.parseToJsonElement(ffmpegProbeJson).jsonObject
            json["selected_accel"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "cpu"
        } catch (_: Throwable) {
            "cpu"
        }
    }

    private fun resolveProbedFfmpegPath(ffmpegProbeJson: String): String {
        if (ffmpegProbeJson.isBlank()) return "ffmpeg"
        return try {
            val json = AppUtil.GlobalVars.json.parseToJsonElement(ffmpegProbeJson).jsonObject
            json["path"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "ffmpeg"
        } catch (_: Throwable) {
            "ffmpeg"
        }
    }
}
