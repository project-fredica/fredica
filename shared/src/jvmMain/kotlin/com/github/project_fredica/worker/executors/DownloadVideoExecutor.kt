package com.github.project_fredica.worker.executors

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.db.Task
import com.github.project_fredica.worker.ExecuteResult
import com.github.project_fredica.worker.TaskExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Downloads a video from a URL using curl.
 *
 * Payload JSON: `{"url": "https://...", "output_path": "/data/video.mp4"}`
 * Result JSON:  `{"output_path": "/data/video.mp4"}`
 */
object DownloadVideoExecutor : TaskExecutor {
    override val taskType = "DOWNLOAD_VIDEO"
    private val logger = createLogger()

    @Serializable
    private data class Payload(val url: String, val output_path: String)

    override suspend fun execute(task: Task): ExecuteResult = withContext(Dispatchers.IO) {
        val payload = try {
            Json.decodeFromString<Payload>(task.payload)
        } catch (e: Throwable) {
            return@withContext ExecuteResult(error = "Invalid payload: ${e.message}", errorType = "PAYLOAD_ERROR")
        }

        logger.info("DownloadVideoExecutor: downloading ${payload.url} → ${payload.output_path}")

        val proc = ProcessBuilder("curl", "-L", "-o", payload.output_path, payload.url)
            .redirectErrorStream(true)
            .start()

        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            val errOutput = proc.inputStream.bufferedReader().readText()
            return@withContext ExecuteResult(
                error = "curl exited with code $exitCode: $errOutput",
                errorType = "DOWNLOAD_ERROR",
            )
        }

        ExecuteResult(result = """{"output_path":"${payload.output_path}"}""")
    }
}
