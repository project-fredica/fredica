package com.github.project_fredica.worker.executors

import com.github.project_fredica.apputil.PyCallGuard
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJsonModel
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
import java.security.MessageDigest

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
 * 检测 output_dir 下是否存在 `extract_audio.done`，
 * 并比对其中记录的 input_video_path SHA-256 hash。
 * hash 不匹配时视为输入文件已变更，重新提取。
 *
 * ## 并发保护
 * 同一 outputDir 的任务通过 [PyCallGuard] 串行化，防止并发重复提取。
 */
object ExtractAudioExecutor : WebSocketTaskExecutor() {
    override val taskType = "EXTRACT_AUDIO"
    private val logger = createLogger()
    private val guard = PyCallGuard()

    @Serializable
    private data class Payload(
        @SerialName("input_video_path")   val inputVideoPath: String,
        @SerialName("output_dir")         val outputDir: String,
        @SerialName("chunk_duration_sec") val chunkDurationSec: Int = 300,
    )

    @Serializable
    private data class DoneFile(
        @SerialName("input_hash") val inputHash: String,
        @SerialName("chunk_duration_sec") val chunkDurationSec: Int,
    )

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(65536)
            var n: Int
            while (ins.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override suspend fun canSkip(task: Task): Boolean {
        val payload = runCatching { Json.decodeFromString<Payload>(task.payload) }.getOrNull() ?: return false
        val doneFile = File(payload.outputDir).resolve("extract_audio.done")
        if (!doneFile.exists()) {
            logger.debug("ExtractAudioExecutor.canSkip: done 文件不存在 outputDir=${payload.outputDir}")
            return false
        }
        val done = doneFile.readText().loadJsonModel<DoneFile>().getOrNull()
        if (done == null) {
            logger.debug("ExtractAudioExecutor.canSkip: done 文件解析失败，重新提取")
            return false
        }
        val inputFile = File(payload.inputVideoPath)
        if (!inputFile.exists()) {
            logger.debug("ExtractAudioExecutor.canSkip: 输入文件不存在 path=${payload.inputVideoPath}")
            return false
        }
        val currentHash = withContext(Dispatchers.IO) { sha256(inputFile) }
        val hashMatch = done.inputHash == currentHash
        val durationMatch = done.chunkDurationSec == payload.chunkDurationSec
        logger.debug(
            "ExtractAudioExecutor.canSkip: hashMatch=$hashMatch durationMatch=$durationMatch" +
            " storedHash=${done.inputHash.take(12)}… currentHash=${currentHash.take(12)}…"
        )
        return hashMatch && durationMatch
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

        guard.withLock(payload.outputDir) {
            // 加锁后再次检查，防止并发任务重复提取
            val inputFile = File(payload.inputVideoPath)
            val doneFile = File(payload.outputDir).resolve("extract_audio.done")
            if (doneFile.exists() && inputFile.exists()) {
                val done = doneFile.readText().loadJsonModel<DoneFile>().getOrNull()
                if (done != null) {
                    val currentHash = sha256(inputFile)
                    if (done.inputHash == currentHash && done.chunkDurationSec == payload.chunkDurationSec) {
                        logger.debug("ExtractAudioExecutor: 加锁后二次检查命中缓存，跳过 [taskId=${task.id}]")
                        return@withLock ExecuteResult(result = "{\"skipped\":true}")
                    }
                }
            }

            File(payload.outputDir).mkdirs()

            val paramJson = buildValidJson {
                kv("video_path", payload.inputVideoPath)
                kv("output_dir", payload.outputDir)
                kv("chunk_duration_sec", payload.chunkDurationSec)
            }.str

            logger.info("ExtractAudioExecutor: 开始提取音频 input=${payload.inputVideoPath} [taskId=${task.id}]")

            try {
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
                    // 写入 done 文件，记录 hash 和切段参数
                    if (inputFile.exists()) {
                        val hash = sha256(inputFile)
                        val doneContent = buildValidJson {
                            kv("input_hash", hash)
                            kv("chunk_duration_sec", payload.chunkDurationSec)
                        }.str
                        doneFile.writeText(doneContent)
                        logger.debug("ExtractAudioExecutor: 写入 done 文件 hash=${hash.take(12)}… [taskId=${task.id}]")
                    }
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
}
