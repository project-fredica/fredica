package com.github.project_fredica.worker.executors

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskService
import com.github.project_fredica.python.FasterWhisperInstallService
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Whisper 语音识别（单段音频文件）。
 *
 * ## Payload 格式
 * ```json
 * {
 *   "audio_path":  "/path/to/chunk.m4a",
 *   "language":    "zh",         // 可选，null 为自动检测
 *   "model_size":  "large-v3",   // 可选
 *   "output_path": "/path/to/transcript.json"  // 结果写入路径（同目录下同时写 transcript.srt）
 * }
 * ```
 *
 * ## 输出文件
 * - `output_path`（JSON）：`{"segments":[{"start":0.0,"end":1.5,"text":"..."},...], "language":"zh"}`
 * - `<output_dir>/transcript.srt`：标准 SRT 格式，供字幕列表直接读取
 *
 * ## 跳过机制（canSkip）
 * 检测 output_path 是否已存在。
 */
object TranscribeExecutor : WebSocketTaskExecutor() {
    override val taskType = "TRANSCRIBE"
    private val logger = createLogger()

    @Serializable
    private data class Payload(
        @SerialName("audio_path")    val audioPath: String,
        val language: String? = null,
        @SerialName("model_size")    val modelSize: String? = null,
        @SerialName("output_path")   val outputPath: String? = null,
        @SerialName("allow_download") val allowDownload: Boolean = false,
    )

    private data class Segment(val start: Double, val end: Double, val text: String)

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

        val paramJson = buildJsonObject {
            put("audio_path", payload.audioPath)
            if (payload.language != null) put("language", payload.language)
            if (payload.modelSize != null) put("model_name", payload.modelSize)
            put("device", "auto")
            put("allow_download", payload.allowDownload)
        }.toString()

        logger.info("TranscribeExecutor: 开始转录 audio=${payload.audioPath} [taskId=${task.id}]")

        // 确保 faster-whisper 已安装（pip 会先检查是否已安装，通常很快）
        TaskService.repo.updateStatusText(task.id, "正在检查 faster-whisper 依赖…")
        val installError = FasterWhisperInstallService.ensureInstalled()
        TaskService.repo.updateStatusText(task.id, null)
        if (installError != null) {
            return@withContext ExecuteResult(error = "faster-whisper 安装失败: $installError", errorType = "INSTALL_ERROR")
        }

        // Collect segments streamed from Python before the final "done" message
        val segments = CopyOnWriteArrayList<Segment>()

        return@withContext try {
            val result = PythonUtil.Py314Embed.PyUtilServer.websocketTask(
                pth = "/audio/transcribe-chunk-task",
                paramJson = paramJson,
                onProgress = { pct -> TaskService.repo.updateProgress(task.id, pct) },
                onRawMessage = { line ->
                    runCatching {
                        val obj = Json.parseToJsonElement(line).let { it as? JsonObject } ?: return@runCatching
                        if (obj["type"]?.jsonPrimitive?.content == "segment") {
                            val start = obj["start"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@runCatching
                            val end   = obj["end"]?.jsonPrimitive?.content?.toDoubleOrNull()   ?: return@runCatching
                            val text  = obj["text"]?.jsonPrimitive?.content                    ?: return@runCatching
                            segments.add(Segment(start, end, text))
                        }
                    }
                },
                cancelSignal = cancelSignal,
                pauseChannel = pauseResumeChannels.pause,
                resumeChannel = pauseResumeChannels.resume,
            )
            if (result == null) {
                logger.info("TranscribeExecutor: 已取消 [taskId=${task.id}]")
                ExecuteResult(error = "用户已取消", errorType = "CANCELLED")
            } else {
                payload.outputPath?.let { outPath ->
                    val outFile = File(outPath)
                    outFile.parentFile?.mkdirs()

                    // Extract detected language from done message
                    val language = runCatching {
                        Json.parseToJsonElement(result).let { it as? JsonObject }
                            ?.get("language")?.jsonPrimitive?.content
                    }.getOrNull() ?: payload.language ?: "und"

                    // Write segments JSON
                    val segmentsJson = buildString {
                        append("{\"segments\":[")
                        segments.forEachIndexed { i, seg ->
                            if (i > 0) append(",")
                            append("{\"start\":${seg.start},\"end\":${seg.end},\"text\":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(seg.text))}}")
                        }
                        append("],\"language\":\"$language\"}")
                    }
                    outFile.writeText(segmentsJson)

                    // Write SRT alongside the JSON
                    val srtFile = outFile.resolveSibling("transcript.srt")
                    srtFile.writeText(buildSrt(segments))
                    logger.info("TranscribeExecutor: 写入 ${segments.size} 段 → ${outFile.name} + transcript.srt [taskId=${task.id}]")
                }
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

    private fun buildSrt(segments: List<Segment>): String = buildString {
        segments.forEachIndexed { i, seg ->
            appendLine(i + 1)
            appendLine("${formatSrtTime(seg.start)} --> ${formatSrtTime(seg.end)}")
            appendLine(seg.text)
            appendLine()
        }
    }

    private fun formatSrtTime(sec: Double): String {
        val totalMs = (sec * 1000).toLong()
        val ms = totalMs % 1000
        val s  = (totalMs / 1000) % 60
        val m  = (totalMs / 60000) % 60
        val h  = totalMs / 3600000
        return "%02d:%02d:%02d,%03d".format(h, m, s, ms)
    }
}
