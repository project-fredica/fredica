package com.github.project_fredica.worker.executors

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.exception
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskService
import com.github.project_fredica.python.PythonUtil
import com.github.project_fredica.worker.ExecuteResult
import com.github.project_fredica.worker.TaskCancelService
import com.github.project_fredica.worker.TaskPauseResumeChannels
import com.github.project_fredica.worker.TaskPauseResumeService
import com.github.project_fredica.worker.TaskExecutor
import com.github.project_fredica.worker.WebSocketTaskExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 通过 Python WebSocket 端点下载 B站视频（视频流 + 音频流），
 * 实时将进度（0–100%）写回 task.progress 字段。
 *
 * ## Payload 格式
 * ```json
 * {
 *   "bvid":        "BV1xxx",          // 必填，B站视频 BV 号
 *   "page":        1,                  // 分P编号，默认 1
 *   "output_path": "/data/.../v.mp4", // 可选，输出路径（取其父目录作为输出目录）
 *   "check_skip":  true               // 可选，WorkflowRun 触发时设为 true 启用跳过检测
 * }
 * ```
 *
 * ## 输出目录解析
 * - 若 payload 含 `output_path`：取其父目录
 * - 否则：使用 `AppUtil.Paths.materialMediaDir(task.materialId)`
 *
 * ## 成功结果
 * Python 端返回的原始 done 消息，写入 task.result：
 * `{"type":"done","video_path":"...","audio_path":"..."}`
 *
 * ## 跳过机制（canSkip）
 * 检测输出目录下是否存在 `download_m4s.done` 或 `download_flv.done` 标志文件。
 * 这两个文件由 Python 下载完成后写入，分别对应 M4S 双流和 FLV 单流两种格式。
 *
 * ## 取消/暂停/恢复
 * 通过 [TaskCancelService] / [TaskPauseResumeService] 注册信号，
 * 经由 websocketTask 转发给 Python 端的 cancel_event / resume_event。
 */
object DownloadBilibiliVideoExecutor : WebSocketTaskExecutor() {
    override val taskType = "DOWNLOAD_BILIBILI_VIDEO"
    private val logger = createLogger()

    @Serializable
    private data class Payload(
        val bvid: String? = null,
        val page: Int = 1,
        @SerialName("output_path") val outputPath: String? = null,
        /** WorkflowRun 触发时设为 true，允许 WorkerEngine 在 .done 文件存在时跳过本任务 */
        @SerialName("check_skip") val checkSkip: Boolean = false,
    )

    /**
     * 检查下载是否可跳过。
     *
     * 条件：输出目录下存在 `download_m4s.done`（M4S 双流）或 `download_flv.done`（FLV 单流）。
     * 这两个标志文件由 Python 下载成功后写入，表示原始流文件已就绪。
     */
    override suspend fun canSkip(task: Task): Boolean {
        val payload = runCatching { Json.decodeFromString<Payload>(task.payload) }.getOrNull() ?: return false
        val outputDir = if (payload.outputPath != null) {
            File(payload.outputPath).parentFile ?: return false
        } else {
            AppUtil.Paths.materialMediaDir(task.materialId)
        }
        val m4sDone = outputDir.resolve("download_m4s.done").exists()
        val flvDone = outputDir.resolve("download_flv.done").exists()
        logger.debug("DownloadBilibiliVideoExecutor.canSkip: dir=${outputDir.absolutePath} m4s=$m4sDone flv=$flvDone")
        return m4sDone || flvDone
    }

    override suspend fun executeWithSignals(
        task: Task,
        cancelSignal: CompletableDeferred<Unit>,
        pauseResumeChannels: TaskPauseResumeChannels,
    ): ExecuteResult = withContext(Dispatchers.IO) {
        val payload = try {
            Json.decodeFromString<Payload>(task.payload)
        } catch (e: Throwable) {
            logger.error("DownloadBilibiliVideoExecutor: payload 解析失败，taskId=${task.id}: ${e.message}")
            return@withContext ExecuteResult(error = "Invalid payload: ${e.message}", errorType = "PAYLOAD_ERROR")
        }

        val bvid = payload.bvid ?: run {
            logger.error("DownloadBilibiliVideoExecutor: payload 缺少 bvid，taskId=${task.id}")
            return@withContext ExecuteResult(
                error = "bilibili source_type requires bvid in payload", errorType = "PAYLOAD_ERROR"
            )
        }

        val outputDir = if (payload.outputPath != null) {
            File(payload.outputPath).parent ?: payload.outputPath
        } else {
            AppUtil.Paths.materialMediaDir(task.materialId).absolutePath
        }

        logger.info("DownloadBilibiliVideoExecutor: 开始下载 bvid=$bvid P${payload.page} → $outputDir [taskId=${task.id}]")

        val paramJson = buildValidJson { kv("output_dir", outputDir) }.str

        return@withContext try {
            val result = PythonUtil.Py314Embed.PyUtilServer.websocketTask(
                pth = "/bilibili/video/download-task/$bvid/${payload.page}",
                paramJson = paramJson,
                onProgress = { pct ->
                    logger.debug("DownloadBilibiliVideoExecutor: 下载进度 $pct% [taskId=${task.id}]")
                    TaskService.repo.updateProgress(task.id, pct)
                },
                onPausable = { pausable ->
                    TaskService.repo.updatePausable(task.id, pausable)
                },
                cancelSignal = cancelSignal,
                pauseChannel = pauseResumeChannels.pause,
                resumeChannel = pauseResumeChannels.resume,
            )
            if (result == null) {
                logger.info("DownloadBilibiliVideoExecutor: 下载已取消 [taskId=${task.id}]")
                ExecuteResult(error = "用户已取消下载", errorType = "CANCELLED")
            } else {
                logger.info("DownloadBilibiliVideoExecutor: 下载完成 bvid=$bvid [taskId=${task.id}]")
                ExecuteResult(result = result)
            }
        } catch (e: Throwable) {
            // 取消信号已触发时，WebSocket 关闭可能抛出异常（如 ClosedReceiveChannelException），
            // 此时应视为用户主动取消而非下载失败，避免触发重试机制。
            if (cancelSignal.isCompleted) {
                logger.info("DownloadBilibiliVideoExecutor: 取消信号已触发，忽略异常 [taskId=${task.id}]: ${e.message}")
                ExecuteResult(error = "用户已取消下载", errorType = "CANCELLED")
            } else {
                logger.error("DownloadBilibiliVideoExecutor: 下载失败 bvid=$bvid [taskId=${task.id}]: ${e.message}")
                ExecuteResult(error = "Bilibili download failed: ${e.message}", errorType = "DOWNLOAD_ERROR")
            }
        }
    }
}
