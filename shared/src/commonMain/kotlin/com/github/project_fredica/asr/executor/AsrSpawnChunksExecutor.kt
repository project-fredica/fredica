package com.github.project_fredica.asr.executor

import com.github.project_fredica.asr.model.AsrSpawnChunksPayload
import com.github.project_fredica.asr.model.TranscribePayload
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.loadJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskId
import com.github.project_fredica.db.TaskService
import com.github.project_fredica.db.TaskStatusService
import com.github.project_fredica.worker.ExecuteResult
import com.github.project_fredica.worker.TaskExecutor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 动态创建 TRANSCRIBE 子任务的中间 Executor。
 *
 * ## 任务链
 *   EXTRACT_AUDIO → ASR_SPAWN_CHUNKS → N × TRANSCRIBE（并行，各自依赖本任务）
 *
 * ## 工作流程
 * 1. 从前置任务 EXTRACT_AUDIO 的 result 中读取 chunks 列表
 * 2. 为每个 chunk 创建一个 TRANSCRIBE 任务（dependsOn = [本任务 ID]）
 * 3. 各 TRANSCRIBE 任务携带正确的 chunk_index、total_chunks、chunk_offset_sec
 *
 * ## Payload 格式
 * ```json
 * {
 *   "extract_audio_task_id": "uuid",
 *   "language": "zh",
 *   "model_size": "large-v3",
 *   "output_dir": "/path/to/asr_result",
 *   "chunk_duration_sec": 300,
 *   "allow_download": false
 * }
 * ```
 */
object AsrSpawnChunksExecutor : TaskExecutor {
    override val taskType = "ASR_SPAWN_CHUNKS"
    private val logger = createLogger()

    override suspend fun execute(task: Task): ExecuteResult {
        val payload = try {
            task.payload.loadJsonModel<AsrSpawnChunksPayload>().getOrThrow()
        } catch (e: Throwable) {
            logger.error("AsrSpawnChunksExecutor: payload 解析失败 taskId=${task.id}: ${e.message}")
            return ExecuteResult(error = "Invalid payload: ${e.message}", errorType = "PAYLOAD_ERROR")
        }

        // 1. 读取前置 EXTRACT_AUDIO 任务的 result
        val extractTask = TaskService.repo.findById(payload.extractAudioTaskId)
        if (extractTask == null) {
            logger.error("AsrSpawnChunksExecutor: 前置任务不存在 extractAudioTaskId=${payload.extractAudioTaskId}")
            return ExecuteResult(error = "Predecessor task not found", errorType = "DEPENDENCY_ERROR")
        }
        if (extractTask.result.isNullOrBlank()) {
            logger.error("AsrSpawnChunksExecutor: 前置任务无结果 extractAudioTaskId=${payload.extractAudioTaskId}")
            return ExecuteResult(error = "Predecessor task has no result", errorType = "DEPENDENCY_ERROR")
        }

        // 2. 解析 chunks 列表：{"type":"done","chunks":[{"path":"...","index":0}, ...]}
        val resultObj = try {
            extractTask.result.loadJson().getOrThrow().jsonObject
        } catch (e: Throwable) {
            logger.error("AsrSpawnChunksExecutor: 前置任务 result 解析失败: ${e.message}")
            return ExecuteResult(error = "Cannot parse predecessor result: ${e.message}", errorType = "DEPENDENCY_ERROR")
        }

        val chunks = resultObj["chunks"]?.jsonArray
        if (chunks == null || chunks.isEmpty()) {
            logger.error("AsrSpawnChunksExecutor: 前置任务 result 中无 chunks")
            return ExecuteResult(error = "No chunks in predecessor result", errorType = "DEPENDENCY_ERROR")
        }

        val totalChunks = chunks.size
        logger.info("AsrSpawnChunksExecutor: 发现 $totalChunks 个 chunk，开始创建 TRANSCRIBE 任务 [taskId=${task.id}]")

        // 3. 为每个 chunk 创建 TRANSCRIBE 任务
        val transcribeTasks = chunks.map { chunkElem ->
            val chunkObj = chunkElem.jsonObject
            val chunkPath = chunkObj["path"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("chunk missing 'path' field")
            val chunkIndex = chunkObj["index"]?.jsonPrimitive?.int
                ?: throw IllegalStateException("chunk missing 'index' field")
            // actual_start_sec = 音频文件的实际起始时间（含 padding），用于将 whisper 相对时间戳转为绝对时间戳
            val chunkOffsetSec = chunkObj["actual_start_sec"]?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: (chunkIndex.toDouble() * payload.chunkDurationSec)
            // core region：合并时只保留中点落在核心区域内的 segment
            val coreStartSec = chunkObj["core_start_sec"]?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: (chunkIndex.toDouble() * payload.chunkDurationSec)
            val coreEndSec = chunkObj["core_end_sec"]?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: ((chunkIndex + 1).toDouble() * payload.chunkDurationSec)

            val transcribePayload = AppUtil.dumpJsonStr(
                TranscribePayload(
                    audioPath = chunkPath,
                    language = payload.language,
                    modelSize = payload.modelSize,
                    outputDir = payload.outputDir,
                    chunkIndex = chunkIndex,
                    totalChunks = totalChunks,
                    chunkOffsetSec = chunkOffsetSec,
                    coreStartSec = coreStartSec,
                    coreEndSec = coreEndSec,
                    allowDownload = payload.allowDownload,
                )
            ).getOrThrow().str

            val taskId = TaskId.random().value
            Task(
                id = taskId,
                type = "TRANSCRIBE",
                workflowRunId = task.workflowRunId,
                materialId = task.materialId,
                payload = transcribePayload,
                priority = task.priority,
                dependsOn = "[\"${task.id}\"]",
                maxRetries = 0,
                createdAt = System.currentTimeMillis() / 1000L,
            )
        }

        // 4. 批量写入 DB
        TaskStatusService.createAll(transcribeTasks)
        logger.info("AsrSpawnChunksExecutor: 已创建 ${transcribeTasks.size} 个 TRANSCRIBE 任务 [taskId=${task.id}]")

        // 返回创建的任务 ID 列表
        val resultJson = buildJsonObject {
            put("total_chunks", totalChunks)
            put("transcribe_task_ids", JsonArray(
                transcribeTasks.map { JsonPrimitive(it.id) }
            ))
        }.toString()

        return ExecuteResult(result = resultJson)
    }
}
