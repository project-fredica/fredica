package com.github.project_fredica.worker.executors

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.db.Task
import com.github.project_fredica.python.PythonUtil
import com.github.project_fredica.worker.ExecuteResult
import com.github.project_fredica.worker.TaskExecutor
import io.ktor.http.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Transcribes a single audio chunk via the Python faster-whisper service.
 *
 * Payload JSON:
 * ```json
 * {"audio_path": "/data/chunks/chunk_000.wav", "model": "large-v3", "language": "zh"}
 * ```
 *
 * Result JSON (from Python): `{"segments": [...], "text": "...", "language": "zh"}`
 */
object TranscribeChunkExecutor : TaskExecutor {
    override val taskType = "TRANSCRIBE_CHUNK"
    private val logger = createLogger()

    @Serializable
    private data class Payload(
        val audio_path: String,
        val model: String = "large-v3",
        val language: String? = null,
    )

    override suspend fun execute(task: Task): ExecuteResult = withContext(Dispatchers.IO) {
        val payload = try {
            Json.decodeFromString<Payload>(task.payload)
        } catch (e: Throwable) {
            return@withContext ExecuteResult(error = "Invalid payload: ${e.message}", errorType = "PAYLOAD_ERROR")
        }

        logger.info("TranscribeChunkExecutor: transcribing ${payload.audio_path} model=${payload.model}")

        val body = Json.encodeToString(Payload.serializer(), payload)
        val result = try {
            PythonUtil.Py314Embed.PyUtilServer.requestText(
                method = HttpMethod.Post,
                p = "/transcribe/chunk",
                body = body,
                requestTimeoutMs = 30 * 60 * 1_000L, // 30 minutes
            )
        } catch (e: Throwable) {
            return@withContext ExecuteResult(
                error = "Python transcribe failed: ${e.message}",
                errorType = "TRANSCRIBE_ERROR",
            )
        }

        ExecuteResult(result = result)
    }
}
