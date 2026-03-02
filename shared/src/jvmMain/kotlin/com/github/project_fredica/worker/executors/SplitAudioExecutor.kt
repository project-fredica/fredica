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
import java.io.File

/**
 * Splits a WAV file into fixed-length chunks using ffmpeg segment muxer.
 *
 * Payload JSON: `{"segment_seconds": 600, "output_dir": "/data/chunks/"}` (both optional)
 * Input path is resolved from predecessor EXTRACT_AUDIO task's result.
 *
 * Result JSON: `{"chunks": ["/data/chunks/chunk_000.wav", ...]}`
 */
object SplitAudioExecutor : TaskExecutor {
    override val taskType = "SPLIT_AUDIO"
    private val logger = createLogger()

    @Serializable
    private data class Payload(
        val segment_seconds: Int = 600,
        val output_dir: String? = null,
    )

    override suspend fun execute(task: Task): ExecuteResult = withContext(Dispatchers.IO) {
        val inputPath = resolveInputPath(task)
            ?: return@withContext ExecuteResult(
                error = "Could not resolve audio input path from depends_on",
                errorType = "DEPENDENCY_ERROR",
            )

        val payload = try {
            Json.decodeFromString<Payload>(task.payload)
        } catch (e: Throwable) {
            return@withContext ExecuteResult(error = "Invalid payload: ${e.message}", errorType = "PAYLOAD_ERROR")
        }

        val outputDir = payload.output_dir
            ?: File(inputPath).parent + "/chunks_${task.id.take(8)}/"
        File(outputDir).mkdirs()

        val pattern = "$outputDir/chunk_%03d.wav"
        logger.info("SplitAudioExecutor: splitting $inputPath → $pattern (${payload.segment_seconds}s)")

        val proc = ProcessBuilder(
            "ffmpeg", "-y", "-i", inputPath,
            "-f", "segment",
            "-segment_time", payload.segment_seconds.toString(),
            "-c", "copy",
            pattern,
        ).redirectErrorStream(true).start()

        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            val errOutput = proc.inputStream.bufferedReader().readText()
            return@withContext ExecuteResult(
                error = "ffmpeg segment failed ($exitCode): ${errOutput.takeLast(500)}",
                errorType = "FFMPEG_ERROR",
            )
        }

        val chunks = File(outputDir).listFiles { f -> f.name.endsWith(".wav") }
            ?.sortedBy { it.name }
            ?.map { it.absolutePath }
            ?: emptyList()

        val chunksJson = chunks.joinToString(",", "[", "]") { "\"$it\"" }
        ExecuteResult(result = """{"chunks":$chunksJson}""")
    }

    private suspend fun resolveInputPath(task: Task): String? {
        val ids = parseIds(task.dependsOn)
        for (id in ids) {
            val dep = TaskService.repo.listByPipeline(task.pipelineId).find { it.id == id }
            val result = dep?.result ?: continue
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
