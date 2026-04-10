package com.github.project_fredica.worker.executors

import com.github.project_fredica.apputil.PyCallGuard
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

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
        @SerialName("overlap_sec")        val overlapSec: Int = 60,
    )

    @Serializable
    private data class DoneFile(
        @SerialName("input_hash") val inputHash: String,
        @SerialName("chunk_duration_sec") val chunkDurationSec: Int,
        @SerialName("overlap_sec") val overlapSec: Int = 0,
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

    /**
     * 扫描 outputDir 中已有的 chunk_XXXX.m4a 文件，重建 chunks JSON 结果。
     * 用于 canSkip / 加锁后二次检查命中缓存时，返回与 Python 端一致的 result 格式，
     * 使 AsrSpawnChunksExecutor 能正确读取 chunks 列表。
     */
    private fun rebuildChunksResult(outputDir: File, chunkDurationSec: Int, overlapSec: Int): String {
        val chunkFiles = outputDir.listFiles { f -> f.name.matches(Regex("""chunk_\d{4}\.m4a""")) }
            ?.sortedBy { it.name }
            ?: emptyList()
        val nChunks = chunkFiles.size
        // 需要知道总时长来计算最后一个 chunk 的 core_end，但 done 文件不记录总时长。
        // 用 nChunks * chunkDurationSec 作为估算上界（最后一个 chunk 的 core_end 可能偏大，
        // 但 TranscribeExecutor 合并时最后一个 chunk 用闭区间，不会丢 segment）。
        val estimatedDuration = nChunks.toDouble() * chunkDurationSec
        val chunks = JsonArray(chunkFiles.mapIndexed { i, f ->
            val coreStart = i.toDouble() * chunkDurationSec
            val coreEnd = min((i + 1).toDouble() * chunkDurationSec, estimatedDuration)
            val actualStart = max(0.0, coreStart - overlapSec)
            buildJsonObject {
                put("path", f.absolutePath)
                put("index", i)
                put("core_start_sec", coreStart)
                put("core_end_sec", coreEnd)
                put("actual_start_sec", actualStart)
            }
        })
        return buildJsonObject {
            put("type", "done")
            put("skipped", true)
            put("chunks", chunks)
        }.toString()
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
        val overlapMatch = done.overlapSec == payload.overlapSec
        logger.debug(
            "ExtractAudioExecutor.canSkip: hashMatch=$hashMatch durationMatch=$durationMatch overlapMatch=$overlapMatch" +
            " storedHash=${done.inputHash.take(12)}… currentHash=${currentHash.take(12)}…"
        )
        return hashMatch && durationMatch && overlapMatch
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
                    if (done.inputHash == currentHash && done.chunkDurationSec == payload.chunkDurationSec && done.overlapSec == payload.overlapSec) {
                        logger.debug("ExtractAudioExecutor: 加锁后二次检查命中缓存，跳过 [taskId=${task.id}]")
                        return@withLock ExecuteResult(result = rebuildChunksResult(File(payload.outputDir), payload.chunkDurationSec, payload.overlapSec))
                    }
                }
            }

            File(payload.outputDir).mkdirs()

            val paramJson = buildJsonObject {
                put("video_path", payload.inputVideoPath)
                put("output_dir", payload.outputDir)
                put("chunk_duration_sec", payload.chunkDurationSec)
                put("overlap_sec", payload.overlapSec)
            }.toString()

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
                        val doneContent = buildJsonObject {
                            put("input_hash", hash)
                            put("chunk_duration_sec", payload.chunkDurationSec)
                            put("overlap_sec", payload.overlapSec)
                        }.toString()
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
