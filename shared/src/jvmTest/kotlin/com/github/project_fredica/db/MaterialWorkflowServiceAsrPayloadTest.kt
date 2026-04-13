package com.github.project_fredica.db

// =============================================================================
// MaterialWorkflowServiceAsrPayloadTest
// =============================================================================
//
// 被测对象：MaterialWorkflowService.startWhisperTranscribe() 生成的 Task payload
//
// 设计说明：
//   startWhisperTranscribe 创建两步任务链：
//     - EXTRACT_AUDIO：使用 chunk_duration_sec=300（5分钟/段），FFmpeg 产出多个 chunk_XXXX.m4a
//     - ASR_SPAWN_CHUNKS：读取 EXTRACT_AUDIO 的 result，动态创建 N 个 TRANSCRIBE 任务
//
//   TRANSCRIBE 任务由 AsrSpawnChunksExecutor 在运行时动态创建，不在此测试范围内。
//
// 测试矩阵：
//   P1. ASR_SPAWN_CHUNKS payload 包含正确的 extract_audio_task_id（指向 EXTRACT_AUDIO 任务）
//   P2. EXTRACT_AUDIO output_dir（asr_audio/）与 ASR_SPAWN_CHUNKS output_dir（asr_result/）不同
//   P3. ASR_SPAWN_CHUNKS payload 包含正确的 language / model_size / chunk_duration_sec
//   P4. 幂等检查：已有活跃任务时返回 AlreadyActive，不重复创建
//   P5. ASR_SPAWN_CHUNKS 依赖 EXTRACT_AUDIO（dependsOn 正确）
// =============================================================================

import com.github.project_fredica.db.TaskPriority
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
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

    private suspend fun startAndGetTasks(matId: String, model: String = "large-v3"): Pair<Task, Task> {
        val result = MaterialWorkflowService.startWhisperTranscribe(makeMaterial(matId), model = model, priority = TaskPriority.DEV_TEST_DEFAULT)
        assertTrue(result is MaterialWorkflowService.StartResult.Started, "应成功创建工作流")
        val tasks = TaskStatusService.listAll(materialId = matId, pageSize = 20).items
        val extract = tasks.first { it.type == "EXTRACT_AUDIO" }
        val spawnChunks = tasks.first { it.type == "ASR_SPAWN_CHUNKS" }
        return extract to spawnChunks
    }

    // ── P1 ───────────────────────────────────────────────────────────────────

    @Test
    fun `P1 - ASR_SPAWN_CHUNKS payload contains correct extract_audio_task_id`() = runBlocking {
        val matId = "asr-p1-${System.nanoTime()}"
        val (extractTask, spawnTask) = startAndGetTasks(matId)

        val spawnPayload = Json.parseToJsonElement(spawnTask.payload).jsonObject
        val extractAudioTaskId = spawnPayload["extract_audio_task_id"]!!.jsonPrimitive.content

        assertEquals(
            extractTask.id,
            extractAudioTaskId,
            "ASR_SPAWN_CHUNKS 的 extract_audio_task_id 应指向 EXTRACT_AUDIO 任务"
        )
    }

    // ── P2 ───────────────────────────────────────────────────────────────────

    @Test
    fun `P2 - EXTRACT_AUDIO output_dir and ASR_SPAWN_CHUNKS output_dir are in separate dirs`() = runBlocking {
        val matId = "asr-p2-${System.nanoTime()}"
        val (extractTask, spawnTask) = startAndGetTasks(matId)

        val extractOutputDir = Json.parseToJsonElement(extractTask.payload)
            .jsonObject["output_dir"]!!.jsonPrimitive.content
        val spawnOutputDir = Json.parseToJsonElement(spawnTask.payload)
            .jsonObject["output_dir"]!!.jsonPrimitive.content

        assertTrue(
            File(extractOutputDir).absolutePath != File(spawnOutputDir).absolutePath,
            "音频目录（asr_audio）与转录结果目录（asr_results/{model}）应分开，" +
                "实际 audioDir=$extractOutputDir transcriptDir=$spawnOutputDir"
        )
        assertTrue(
            spawnOutputDir.replace("\\", "/").contains("asr_results/large-v3"),
            "转录结果目录应包含 asr_results/large-v3，实际=$spawnOutputDir"
        )
    }

    // ── P3 ───────────────────────────────────────────────────────────────────

    @Test
    fun `P3 - ASR_SPAWN_CHUNKS payload has correct language model_size chunk_duration_sec`() = runBlocking {
        val matId = "asr-p3-${System.nanoTime()}"
        val (_, spawnTask) = startAndGetTasks(matId)

        val payload = Json.parseToJsonElement(spawnTask.payload).jsonObject

        assertEquals("auto", payload["language"]!!.jsonPrimitive.content, "未指定语言时应默认为 auto（自动检测）")
        assertEquals("large-v3", payload["model_size"]!!.jsonPrimitive.content, "显式传入 large-v3 时 model_size 应为 large-v3")
        assertEquals(300, payload["chunk_duration_sec"]!!.jsonPrimitive.int, "chunk_duration_sec 应为 300")
        assertEquals(60, payload["overlap_sec"]!!.jsonPrimitive.int, "overlap_sec 应为 60")
        assertEquals(false, payload["allow_download"]!!.jsonPrimitive.boolean, "默认 allow_download 应为 false")
    }

    // ── P4 ───────────────────────────────────────────────────────────────────

    @Test
    fun `P4 - returns AlreadyActive when active tasks exist`() = runBlocking {
        val matId = "asr-p4-${System.nanoTime()}"
        val material = makeMaterial(matId)

        val first = MaterialWorkflowService.startWhisperTranscribe(material, model = "large-v3", priority = TaskPriority.DEV_TEST_DEFAULT)
        assertTrue(first is MaterialWorkflowService.StartResult.Started, "第一次应成功创建")

        val second = MaterialWorkflowService.startWhisperTranscribe(material, model = "large-v3", priority = TaskPriority.DEV_TEST_DEFAULT)
        assertTrue(second is MaterialWorkflowService.StartResult.AlreadyActive, "第二次应返回 AlreadyActive")
    }

    // ── P5 ───────────────────────────────────────────────────────────────────

    @Test
    fun `P5 - ASR_SPAWN_CHUNKS depends on EXTRACT_AUDIO`() = runBlocking {
        val matId = "asr-p5-${System.nanoTime()}"
        val (extractTask, spawnTask) = startAndGetTasks(matId)

        val dependsOn = Json.parseToJsonElement(spawnTask.dependsOn ?: "[]").jsonArray
        assertTrue(dependsOn.any { it.jsonPrimitive.content == extractTask.id },
            "ASR_SPAWN_CHUNKS 应依赖 EXTRACT_AUDIO，dependsOn=${spawnTask.dependsOn}")
    }
}
