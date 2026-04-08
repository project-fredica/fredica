package com.github.project_fredica.db

// =============================================================================
// MaterialWorkflowServiceAsrPayloadTest
// =============================================================================
//
// 被测对象：MaterialWorkflowService.startWhisperTranscribe() 生成的 Task payload
//
// 设计说明：
//   startWhisperTranscribe 是 subtitle-export-asr.md 的最小线性实现（Phase 1–3）：
//     - EXTRACT_AUDIO 使用 chunk_duration_sec=300（5分钟/段），FFmpeg 产出多个 chunk_XXXX.m4a
//     - 当前 TRANSCRIBE 只处理 chunk_0000.m4a（即视频前 5 分钟）
//     - 完整多 chunk 方案（Phase 4–6）待后续实现：
//       EXTRACT_AUDIO → ASR_SPAWN_CHUNKS → ASR_TRANSCRIBE_QUEUE → ASR_MERGE_SUBTITLE
//
//   注意：此函数与"字幕导出"（SRT Export，纯前端下载）无关，
//   两者是 subtitle-export-asr.md 中独立的两个功能。
//
// 测试矩阵：
//   P1. TRANSCRIBE audio_path 指向 chunk_0000.m4a（FFmpeg segment 实际产出的文件名）
//   P2. TRANSCRIBE audio_path 与 EXTRACT_AUDIO output_dir 在同一目录下（路径衔接正确）
//   P3. EXTRACT_AUDIO output_dir（asr_audio/）与 TRANSCRIBE output_path 的父目录（asr_result/）不同
//   P4. 幂等检查：已有活跃任务时返回 AlreadyActive，不重复创建
// =============================================================================

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.ktorm.database.Database
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaterialWorkflowServiceAsrPayloadTest {

    private lateinit var db: Database

    @BeforeTest
    fun setup() = runBlocking {
        val tmpFile = File.createTempFile("asr_payload_test_", ".db").also { it.deleteOnExit() }
        db = Database.connect(
            url = "jdbc:sqlite:${tmpFile.absolutePath}",
            driver = "org.sqlite.JDBC",
        )
        val taskDb = TaskDb(db)
        val workflowRunDb = WorkflowRunDb(db)
        val appConfigDb = AppConfigDb(db)
        taskDb.initialize()
        workflowRunDb.initialize()
        appConfigDb.initialize()
        TaskService.initialize(taskDb)
        WorkflowRunService.initialize(workflowRunDb)
        AppConfigService.initialize(appConfigDb)
    }

    private fun makeMaterial(id: String, videoPath: String = "/fake/video.mp4") = MaterialVideo(
        id = id,
        title = "test",
        sourceType = "local",
        sourceId = "src",
        coverUrl = "",
        description = "",
        duration = 600,
        localVideoPath = videoPath,
        localAudioPath = "",
        transcriptPath = "",
        extra = "{}",
        createdAt = 0L,
        updatedAt = 0L,
    )

    private suspend fun startAndGetTasks(matId: String): Pair<Task, Task> {
        val result = MaterialWorkflowService.startWhisperTranscribe(makeMaterial(matId))
        assertTrue(result is MaterialWorkflowService.StartResult.Started, "应成功创建工作流")
        val tasks = TaskStatusService.listAll(materialId = matId, pageSize = 20).items
        val extract = tasks.first { it.type == "EXTRACT_AUDIO" }
        val transcribe = tasks.first { it.type == "TRANSCRIBE" }
        return extract to transcribe
    }

    // ── P1 ───────────────────────────────────────────────────────────────────

    @Test
    fun `P1 - TRANSCRIBE audio_path points to chunk_0000 not full_audio`() = runBlocking {
        val matId = "asr-p1-${System.nanoTime()}"
        val (_, transcribeTask) = startAndGetTasks(matId)

        val audioPath = Json.parseToJsonElement(transcribeTask.payload)
            .jsonObject["audio_path"]!!.jsonPrimitive.content

        assertTrue(
            audioPath.endsWith("chunk_0000.m4a"),
            "audio_path 应指向 chunk_0000.m4a（FFmpeg segment 实际产出的文件名），实际=$audioPath"
        )
    }

    // ── P2 ───────────────────────────────────────────────────────────────────

    @Test
    fun `P2 - TRANSCRIBE audio_path is inside EXTRACT_AUDIO output_dir`() = runBlocking {
        val matId = "asr-p2-${System.nanoTime()}"
        val (extractTask, transcribeTask) = startAndGetTasks(matId)

        val outputDir = Json.parseToJsonElement(extractTask.payload)
            .jsonObject["output_dir"]!!.jsonPrimitive.content
        val audioPath = Json.parseToJsonElement(transcribeTask.payload)
            .jsonObject["audio_path"]!!.jsonPrimitive.content

        assertEquals(
            File(outputDir).absolutePath,
            File(audioPath).parentFile.absolutePath,
            "TRANSCRIBE audio_path 的父目录应与 EXTRACT_AUDIO output_dir 一致"
        )
    }

    // ── P3 ───────────────────────────────────────────────────────────────────

    @Test
    fun `P3 - EXTRACT_AUDIO output_dir and TRANSCRIBE output_path are in separate dirs`() = runBlocking {
        val matId = "asr-p3-${System.nanoTime()}"
        val (extractTask, transcribeTask) = startAndGetTasks(matId)

        val outputDir = Json.parseToJsonElement(extractTask.payload)
            .jsonObject["output_dir"]!!.jsonPrimitive.content
        val outputPath = Json.parseToJsonElement(transcribeTask.payload)
            .jsonObject["output_path"]!!.jsonPrimitive.content

        val audioParent = File(outputDir).absolutePath
        val transcriptParent = File(outputPath).parentFile.absolutePath

        assertTrue(
            audioParent != transcriptParent,
            "音频目录（asr_audio）与转录结果目录（asr_result）应分开，实际 audioDir=$audioParent transcriptDir=$transcriptParent"
        )
    }

    // ── P4 ───────────────────────────────────────────────────────────────────

    @Test
    fun `P4 - returns AlreadyActive when active tasks exist`() = runBlocking {
        val matId = "asr-p4-${System.nanoTime()}"
        val material = makeMaterial(matId)

        val first = MaterialWorkflowService.startWhisperTranscribe(material)
        assertTrue(first is MaterialWorkflowService.StartResult.Started, "第一次应成功创建")

        val second = MaterialWorkflowService.startWhisperTranscribe(material)
        assertTrue(second is MaterialWorkflowService.StartResult.AlreadyActive, "第二次应返回 AlreadyActive")
    }
}
