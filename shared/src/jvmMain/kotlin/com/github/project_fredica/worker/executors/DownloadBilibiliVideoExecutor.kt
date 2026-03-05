package com.github.project_fredica.worker.executors

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskService
import com.github.project_fredica.python.PythonUtil
import com.github.project_fredica.worker.ExecuteResult
import com.github.project_fredica.worker.TaskCancelService
import com.github.project_fredica.worker.TaskPauseResumeService
import com.github.project_fredica.worker.TaskExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Downloads a B站 video (video + audio streams) via the Python WebSocket endpoint,
 * reporting real-time progress (0–100 %) back into the task row.
 *
 * Payload: `{"bvid": "BV1xxx", "page": 1, "output_path": "/data/media/video.mp4"}`
 *
 * The output directory is derived from [Payload.outputPath] (its parent directory).
 * If [Payload.outputPath] is absent, [AppUtil.Paths.materialMediaDir] for the task's
 * material is used as the output directory.
 *
 * Result JSON on success: the raw `done` message from the Python service,
 * i.e. `{"type":"done","video_path":"...","audio_path":"..."}`.
 */
object DownloadBilibiliVideoExecutor : TaskExecutor {
    override val taskType = "DOWNLOAD_BILIBILI_VIDEO"
    private val logger = createLogger()

    @Serializable
    private data class Payload(
        val bvid: String? = null,
        val page: Int = 1,
        @SerialName("output_path") val outputPath: String? = null,
    )

    override suspend fun execute(task: Task): ExecuteResult = withContext(Dispatchers.IO) {
        val payload = try {
            Json.decodeFromString<Payload>(task.payload)
        } catch (e: Throwable) {
            return@withContext ExecuteResult(error = "Invalid payload: ${e.message}", errorType = "PAYLOAD_ERROR")
        }

        val bvid = payload.bvid ?: return@withContext ExecuteResult(
            error = "bilibili source_type requires bvid in payload", errorType = "PAYLOAD_ERROR"
        )

        val outputDir = if (payload.outputPath != null) {
            File(payload.outputPath).parent ?: payload.outputPath
        } else {
            AppUtil.Paths.materialMediaDir(task.materialId).absolutePath
        }

        logger.info("DownloadBilibiliVideoExecutor: start bvid=$bvid P${payload.page} → $outputDir")

        val paramJson = buildValidJson { kv("output_dir", outputDir) }.str

        val cancelSignal = TaskCancelService.register(task.id)
        val pauseResumeChannels = TaskPauseResumeService.register(task.id)
        return@withContext try {
            val result = PythonUtil.Py314Embed.PyUtilServer.websocketTask(
                pth = "/bilibili/video/download-task/$bvid/${payload.page}",
                paramJson = paramJson,
                onProgress = { pct -> TaskService.repo.updateProgress(task.id, pct) },
                cancelSignal = cancelSignal,
                pauseChannel = pauseResumeChannels.pause,
                resumeChannel = pauseResumeChannels.resume,
            )
            if (result == null) {
                ExecuteResult(error = "用户已取消下载", errorType = "CANCELLED")
            } else {
                ExecuteResult(result = result)
            }
        } catch (e: Throwable) {
            // 取消信号已触发时，WebSocket 关闭可能抛出异常（如 ClosedReceiveChannelException），
            // 此时应视为用户主动取消而非下载失败，避免触发重试机制。
            if (cancelSignal.isCompleted) {
                ExecuteResult(error = "用户已取消下载", errorType = "CANCELLED")
            } else {
                ExecuteResult(error = "Bilibili download failed: ${e.message}", errorType = "DOWNLOAD_ERROR")
            }
        } finally {
            TaskCancelService.unregister(task.id)
            TaskPauseResumeService.unregister(task.id)
            @Suppress("SwallowedException")
            try { TaskService.repo.updatePaused(task.id, false) } catch (_: Exception) {}
        }
    }
}
