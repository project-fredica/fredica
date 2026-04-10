package com.github.project_fredica.asr.material_workflow_ext

import com.github.project_fredica.asr.model.AsrSpawnChunksPayload
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.db.CommonWorkflowService
import com.github.project_fredica.db.MaterialVideo
import com.github.project_fredica.db.MaterialWorkflowService
import com.github.project_fredica.db.TaskId
import com.github.project_fredica.db.TaskStatusService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val ACTIVE_STATUSES = setOf("pending", "claimed", "running")
private val logger = createLogger { "MaterialWorkflowServiceExt" }

/**
 * 启动本地 Whisper 语音识别流程（与"字幕导出"无关，字幕导出是纯前端 SRT 下载）。
 *
 * ## 任务链
 *   EXTRACT_AUDIO（5分钟/段）→ ASR_SPAWN_CHUNKS → N × TRANSCRIBE（动态创建）
 *
 * ASR_SPAWN_CHUNKS 读取 EXTRACT_AUDIO 的 result 获取实际 chunk 数量，
 * 动态创建 N 个 TRANSCRIBE 任务（每个 chunk 一个），各自携带正确的
 * chunk_index / total_chunks / chunk_offset_sec。
 *
 * @param model    模型名称，为空时使用 "large-v3"
 * @param language 语言代码，为空时使用 "zh"
 */
@Suppress("UnusedReceiverParameter")
suspend fun MaterialWorkflowService.startWhisperTranscribe2(
    material: MaterialVideo,
    model: String? = null,
    language: String? = null,
    allowDownload: Boolean = false,
): MaterialWorkflowService.StartResult {
    val hasActive = TaskStatusService.listAll(materialId = material.id, pageSize = 200)
        .items.any {
            it.type in setOf("EXTRACT_AUDIO", "ASR_SPAWN_CHUNKS", "TRANSCRIBE") &&
            it.status in ACTIVE_STATUSES
        }
    if (hasActive) return MaterialWorkflowService.StartResult.AlreadyActive

    val modelName = model?.takeIf { it.isNotBlank() } ?: "large-v3"
    val langCode = language?.takeIf { it.isNotBlank() } ?: "zh"
    logger.debug("startWhisperTranscribe2 materialId=${material.id} model=$modelName lang=$langCode allowDownload=$allowDownload")
    val mediaDir = AppUtil.Paths.materialMediaDir(material.id)
    val inputVideoPath = material.localVideoPath.ifBlank { mediaDir.resolve("video.mp4").absolutePath }
    val audioDir = mediaDir.resolve("asr_audio")
    val transcriptDir = mediaDir.resolve("asr_results").resolve(modelName)
    val extractAudioTaskId = TaskId.random()
    val spawnChunksTaskId = TaskId.random()

    val extractAudioPayload = buildJsonObject {
        put("input_video_path", inputVideoPath)
        put("output_dir", audioDir.absolutePath)
        put("chunk_duration_sec", 300)
        put("overlap_sec", 60)
    }.toString()

    val spawnChunksPayload = AppUtil.dumpJsonStr(
        AsrSpawnChunksPayload(
            extractAudioTaskId = extractAudioTaskId.value,
            language = langCode,
            modelSize = modelName,
            outputDir = transcriptDir.absolutePath,
            chunkDurationSec = 300,
            overlapSec = 60,
            allowDownload = allowDownload,
        )
    ).getOrThrow().str

    val tasks = listOf(
        CommonWorkflowService.TaskDef(
            id = extractAudioTaskId,
            type = "EXTRACT_AUDIO",
            materialId = material.id,
            payload = extractAudioPayload,
            maxRetries = 0,
        ),
        CommonWorkflowService.TaskDef(
            id = spawnChunksTaskId,
            type = "ASR_SPAWN_CHUNKS",
            materialId = material.id,
            payload = spawnChunksPayload,
            dependsOnIds = listOf(extractAudioTaskId),
            maxRetries = 0,
        ),
    )

    val workflowRunId = CommonWorkflowService.createWorkflow(
        template = "whisper_transcribe",
        materialId = material.id,
        tasks = tasks,
    )

    return MaterialWorkflowService.StartResult.Started(
        workflowRunId = workflowRunId,
        extractAudioTaskId = extractAudioTaskId.value,
        spawnChunksTaskId = spawnChunksTaskId.value,
    ).also {
        logger.info("startWhisperTranscribe2 已创建 workflowRunId=$workflowRunId materialId=${material.id}")
    }
}
