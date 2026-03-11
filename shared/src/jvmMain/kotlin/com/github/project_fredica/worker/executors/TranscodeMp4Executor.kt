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
import com.github.project_fredica.worker.TaskPauseResumeChannels
import com.github.project_fredica.worker.TaskPauseResumeService
import com.github.project_fredica.worker.WebSocketTaskExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 通过 Python WebSocket 端点将 B站原始流（.m4s/.flv）转码为 .mp4，
 * 实时将进度（0–100%）写回 task.progress 字段。
 *
 * ## Payload 格式
 * ```json
 * {
 *   "mode":        "from_bilibili_download", // "direct"（默认）或 "from_bilibili_download"
 *   "input_video": "/data/.../video.m4s",    // direct 模式必填
 *   "input_audio": "/data/.../audio.m4s",    // direct 模式可选（FLV 单流时为 null）
 *   "output_path": "/data/.../video.mp4",    // 必填，输出路径
 *   "output_dir":  "/data/.../",             // from_bilibili_download 模式必填
 *   "hw_accel":    "auto",                   // 硬件加速：auto/cuda/amf/qsv/videotoolbox/cpu
 *   "check_skip":  true                      // 可选，WorkflowRun 触发时设为 true
 * }
 * ```
 *
 * ## 模式说明
 * - `direct`：直接指定 input_video / input_audio 路径
 * - `from_bilibili_download`：从 output_dir 中检测 .done 标志文件自动解析输入路径：
 *     - `download_m4s.done` 存在 → 读取 video.m4s + audio.m4s（双流合并）
 *     - `download_flv.done` 存在 → 读取 video.flv（单流直接转封装）
 *     - 两者都不存在 → 返回 INPUT_NOT_FOUND 错误
 *
 * ## hw_accel 解析
 * `auto` 时从 `AppConfig.ffmpegProbeJson` 的 `selected_accel` 字段读取最优加速方案，
 * 由启动时 `/device/detect` 探测写入。若探测结果为空则回退到 `cpu`。
 *
 * ## 跳过机制（canSkip）
 * 检测 output_dir（或 output_path 的父目录）下是否存在 `transcode.done` 标志文件。
 * 该文件由 Python 转码成功后写入。
 *
 * ## 取消/暂停/恢复
 * 通过 [TaskCancelService] / [TaskPauseResumeService] 注册信号，
 * 经由 websocketTask 转发给 Python 端的 cancel_event / resume_event。
 */
object TranscodeMp4Executor : WebSocketTaskExecutor() {
    override val taskType = "TRANSCODE_MP4"
    private val logger = createLogger()

    @Serializable
    private data class Payload(
        val mode: String = "direct",
        @SerialName("input_video") val inputVideo: String = "",
        @SerialName("input_audio") val inputAudio: String? = null,
        @SerialName("output_path") val outputPath: String,
        @SerialName("output_dir") val outputDir: String = "",
        @SerialName("hw_accel") val hwAccel: String = "auto",
        @SerialName("check_skip") val checkSkip: Boolean = false,
    )

    /**
     * 检查转码是否可跳过。
     *
     * 条件：output_dir（或 output_path 的父目录）下存在 `transcode.done` 标志文件。
     * 该文件由 Python FFmpeg 转码成功后写入，表示 .mp4 已生成完毕。
     */
    override suspend fun canSkip(task: Task): Boolean {
        val payload = runCatching { Json.decodeFromString<Payload>(task.payload) }.getOrNull() ?: return false
        val dir = when {
            payload.outputDir.isNotBlank() -> File(payload.outputDir)
            payload.outputPath.isNotBlank() -> File(payload.outputPath).parentFile ?: return false
            else -> return false
        }
        val done = dir.resolve("transcode.done").exists()
        logger.debug("TranscodeMp4Executor.canSkip: dir=${dir.absolutePath} transcode.done=$done")
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
            logger.error("TranscodeMp4Executor: payload 解析失败，taskId=${task.id}: ${e.message}")
            return@withContext ExecuteResult(error = "Invalid payload: ${e.message}", errorType = "PAYLOAD_ERROR")
        }

        // Resolve input paths based on mode
        val (resolvedInputVideo, resolvedInputAudio) = when (payload.mode) {
            // from_bilibili_download：从 output_dir 检测 .done 标志文件自动解析输入路径，
            // 无需在 payload 中显式指定 input_video / input_audio。
            "from_bilibili_download" -> {
                val dir = File(payload.outputDir)
                when {
                    dir.resolve("download_m4s.done").exists() -> {
                        // M4S 双流：video.m4s（视频）+ audio.m4s（音频），需合并转码
                        val v = dir.resolve("video.m4s")
                        val a = dir.resolve("audio.m4s")
                        if (!v.exists() || !a.exists()) {
                            logger.error("TranscodeMp4Executor: M4S 文件不存在 [taskId=${task.id}]: v=${v.absolutePath} a=${a.absolutePath}")
                            return@withContext ExecuteResult(
                                error = "M4S 文件不存在：${v.absolutePath}",
                                errorType = "INPUT_NOT_FOUND"
                            )
                        }
                        logger.info("TranscodeMp4Executor: 检测到 M4S 双流 [taskId=${task.id}]")
                        Pair(v.absolutePath, a.absolutePath)
                    }

                    dir.resolve("download_flv.done").exists() -> {
                        // FLV 单流：video.flv，直接转封装为 mp4
                        val v = dir.resolve("video.flv")
                        if (!v.exists()) {
                            logger.error("TranscodeMp4Executor: FLV 文件不存在 [taskId=${task.id}]: ${v.absolutePath}")
                            return@withContext ExecuteResult(
                                error = "FLV 文件不存在：${v.absolutePath}",
                                errorType = "INPUT_NOT_FOUND"
                            )
                        }
                        logger.info("TranscodeMp4Executor: 检测到 FLV 单流 [taskId=${task.id}]")
                        Pair(v.absolutePath, null)
                    }

                    else -> {
                        // 两种 .done 文件都不存在，说明下载任务尚未完成（不应发生，DAG 保证顺序）
                        logger.error("TranscodeMp4Executor: 未找到 download_*.done 标记文件 [taskId=${task.id}]: dir=${payload.outputDir}")
                        return@withContext ExecuteResult(
                            error = "下载未完成：未找到 download_*.done 标记文件（${payload.outputDir}）",
                            errorType = "INPUT_NOT_FOUND"
                        )
                    }
                }
            }
            // direct 模式：直接使用 payload 中指定的路径
            else -> Pair(payload.inputVideo, payload.inputAudio)
        }

        // Resolve hw_accel: "auto" 时从 AppConfig.ffmpegProbeJson 读取探测结果中的最优加速方案
        val config = AppConfigService.repo.getConfig()
        val resolvedAccel = if (payload.hwAccel == "auto") {
            resolveSelectedAccel(config.ffmpegHwAccel, config.ffmpegProbeJson)
        } else {
            payload.hwAccel
        }

        // ffmpeg_path：优先使用用户配置路径，否则从探测结果中读取，最终回退到系统 PATH 中的 ffmpeg
        val ffmpegPath = config.ffmpegPath.ifBlank { resolveProbedFfmpegPath(config.ffmpegProbeJson) }

        logger.info("TranscodeMp4Executor: 开始转码 $resolvedInputVideo → ${payload.outputPath} [accel=$resolvedAccel, ffmpeg=$ffmpegPath, taskId=${task.id}]")

        val paramJson = buildValidJson {
            kv("input_video", resolvedInputVideo)
            kv("output_path", payload.outputPath)
            kv("hw_accel", resolvedAccel)
            kv("ffmpeg_path", ffmpegPath)
            if (resolvedInputAudio != null) {
                kv("input_audio", resolvedInputAudio)
            }
        }.str

        return@withContext try {
            val result = PythonUtil.Py314Embed.PyUtilServer.websocketTask(
                pth = "/transcode/mp4-task",
                paramJson = paramJson,
                onProgress = { pct ->
                    logger.debug("TranscodeMp4Executor: 转码进度 $pct% [taskId=${task.id}]")
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
                logger.info("TranscodeMp4Executor: 转码已取消 [taskId=${task.id}]")
                ExecuteResult(error = "用户已取消转码", errorType = "CANCELLED")
            } else {
                logger.info("TranscodeMp4Executor: 转码完成 → ${payload.outputPath} [taskId=${task.id}]")
                ExecuteResult(result = result)
            }
        } catch (e: Throwable) {
            // 取消信号已触发时，WebSocket 关闭可能抛出异常，视为用户主动取消
            if (cancelSignal.isCompleted) {
                logger.info("TranscodeMp4Executor: 取消信号已触发，忽略异常 [taskId=${task.id}]: ${e.message}")
                ExecuteResult(error = "用户已取消转码", errorType = "CANCELLED")
            } else {
                logger.error("TranscodeMp4Executor: 转码失败 [taskId=${task.id}]: ${e.message}")
                ExecuteResult(error = "Transcode failed: ${e.message}", errorType = "TRANSCODE_ERROR")
            }
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
