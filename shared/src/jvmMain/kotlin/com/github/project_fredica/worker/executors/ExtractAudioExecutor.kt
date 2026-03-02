package com.github.project_fredica.worker.executors

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskService
import com.github.project_fredica.worker.ExecuteResult
import com.github.project_fredica.worker.TaskExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extracts a 16 kHz mono WAV audio track from a video file using ffmpeg.
 *
 * Payload JSON: `{"output_path": "/data/audio.wav"}` (optional)
 * The input video path is read from the predecessor DOWNLOAD_VIDEO task's result.
 *
 * Result JSON: `{"output_path": "/data/audio.wav"}`
 */
object ExtractAudioExecutor : TaskExecutor {
    override val taskType = "EXTRACT_AUDIO"
    private val logger = createLogger()

    @Serializable
    private data class Payload(val output_path: String? = null)

    override suspend fun execute(task: Task): ExecuteResult = withContext(Dispatchers.IO) {
        val inputPath = resolveInputPath(task)
            ?: return@withContext ExecuteResult(
                error = "Could not resolve input video path from depends_on tasks",
                errorType = "DEPENDENCY_ERROR",
            )

        val payload = try {
            Json.decodeFromString<Payload>(task.payload)
        } catch (e: Throwable) {
            return@withContext ExecuteResult(error = "Invalid payload: ${e.message}", errorType = "PAYLOAD_ERROR")
        }

        val outputPath = payload.output_path
            ?: inputPath.substringBeforeLast('.') + "_audio.wav"

        logger.info("ExtractAudioExecutor: $inputPath → $outputPath")

        val proc = ProcessBuilder(
            "ffmpeg", "-y", "-i", inputPath,
            "-vn", "-acodec", "pcm_s16le", "-ar", "16000", "-ac", "1",
            outputPath,
        ).redirectErrorStream(true).start()

        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            val errOutput = proc.inputStream.bufferedReader().readText()
            return@withContext ExecuteResult(
                error = "ffmpeg exited with code $exitCode: ${errOutput.takeLast(500)}",
                errorType = "FFMPEG_ERROR",
            )
        }

        ExecuteResult(result = """{"output_path":"$outputPath"}""")
    }

    private suspend fun resolveInputPath(task: Task): String? {
        val ids = parseIds(task.dependsOn)
        val allTasks = TaskService.repo.listByPipeline(task.pipelineId)
        for (id in ids) {
            val dep = allTasks.find { it.id == id } ?: continue
            val result = dep.result ?: continue
            try {
                val path = Json.parseToJsonElement(result)
                    .jsonObject["output_path"]?.jsonPrimitive?.content
                if (path != null) return path
            } catch (_: Throwable) { continue }
        }
        return null
    }

    private fun parseIds(json: String): List<String> = try {
        json.trim('[', ']').split(',')
            .map { it.trim('"', ' ') }
            .filter { it.isNotBlank() }
    } catch (_: Throwable) { emptyList() }
}
