package com.github.project_fredica.db

// =============================================================================
// CommonWorkflowServicePriorityTest —— createWorkflow 优先级传播的单元测试
// =============================================================================
//
// 被测对象：CommonWorkflowService.createWorkflow()
//
// 测试目的：验证 TaskDef.priority 正确传播到 Task.priority。
//
// 测试矩阵：
//   1. testPriorityPropagation       — 不同 priority 的 TaskDef 写入后，DB 中 Task.priority 一致
//   2. testMixedPriorityInSameWorkflow — 同一 WorkflowRun 中混合不同优先级
//
// 测试环境：每个测试用例独立的 SQLite 临时文件。
// =============================================================================

import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CommonWorkflowServicePriorityTest {

    private lateinit var db: Database
    private lateinit var taskDb: TaskDb
    private lateinit var workflowRunDb: WorkflowRunDb

    @BeforeTest
    fun setup() = runBlocking {
        val tmpFile = File.createTempFile("cwstest_", ".db").also { it.deleteOnExit() }
        db = Database.connect(
            url    = "jdbc:sqlite:${tmpFile.absolutePath}",
            driver = "org.sqlite.JDBC",
        )
        workflowRunDb = WorkflowRunDb(db)
        taskDb = TaskDb(db)
        workflowRunDb.initialize()
        taskDb.initialize()
        TaskService.initialize(taskDb)
        WorkflowRunService.initialize(workflowRunDb)
    }

    // ── 测试 1：优先级传播 ──────────────────────────────────────────────────

    /**
     * 证明目的：TaskDef.priority 的值被原样传播到 Task.priority。
     *
     * 证明过程：
     *   1. 创建包含 3 个不同 priority 的 TaskDef。
     *   2. 调用 createWorkflow()。
     *   3. 从 DB 读回所有 Task，验证每个 Task 的 priority 与对应 TaskDef 一致。
     */
    @Test
    fun testPriorityPropagation() = runBlocking {
        val taskDefs = listOf(
            CommonWorkflowService.TaskDef(
                type = "TRANSCRIBE", materialId = "mat-1",
                payload = "{}", priority = TaskPriority.TRANSCRIBE_HIGH,
            ),
            CommonWorkflowService.TaskDef(
                type = "TRANSCODE_MP4", materialId = "mat-1",
                payload = "{}", priority = TaskPriority.TRANSCODE_MP4,
            ),
            CommonWorkflowService.TaskDef(
                type = "DOWNLOAD_TORCH", materialId = "mat-1",
                payload = "{}", priority = TaskPriority.DOWNLOAD_TORCH,
            ),
        )

        val wfId = CommonWorkflowService.createWorkflow(
            template = "PRIORITY_TEST",
            materialId = "mat-1",
            tasks = taskDefs,
        )

        val tasks = taskDb.listByWorkflowRun(wfId)
        assertEquals(3, tasks.size, "应创建 3 个任务")

        // 按 type 匹配验证 priority
        val byType = tasks.associateBy { it.type }
        assertEquals(TaskPriority.TRANSCRIBE_HIGH, byType["TRANSCRIBE"]!!.priority,
            "TRANSCRIBE 任务的 priority 应为 ${TaskPriority.TRANSCRIBE_HIGH}")
        assertEquals(TaskPriority.TRANSCODE_MP4, byType["TRANSCODE_MP4"]!!.priority,
            "TRANSCODE_MP4 任务的 priority 应为 ${TaskPriority.TRANSCODE_MP4}")
        assertEquals(TaskPriority.DOWNLOAD_TORCH, byType["DOWNLOAD_TORCH"]!!.priority,
            "DOWNLOAD_TORCH 任务的 priority 应为 ${TaskPriority.DOWNLOAD_TORCH}")
    }

    // ── 测试 2：同一工作流中混合优先级 ──────────────────────────────────────

    /**
     * 证明目的：同一 WorkflowRun 中的多个任务可以有不同的 priority，
     *           且各自独立保存，互不影响。
     *
     * 证明过程：
     *   1. 创建 ASR 工作流的典型任务链：EXTRACT_AUDIO(6) → ASR_SPAWN_CHUNKS(6) → TRANSCRIBE(9)
     *   2. 验证每个任务的 priority 独立正确。
     */
    @Test
    fun testMixedPriorityInSameWorkflow() = runBlocking {
        val extractId = TaskId.random()
        val spawnId = TaskId.random()
        val transcribeId = TaskId.random()

        val taskDefs = listOf(
            CommonWorkflowService.TaskDef(
                id = extractId,
                type = "EXTRACT_AUDIO", materialId = "mat-2",
                payload = "{}", priority = TaskPriority.TRANSCRIBE_MEDIUM,
            ),
            CommonWorkflowService.TaskDef(
                id = spawnId,
                type = "ASR_SPAWN_CHUNKS", materialId = "mat-2",
                payload = "{}", priority = TaskPriority.TRANSCRIBE_MEDIUM,
                dependsOnIds = listOf(extractId),
            ),
            CommonWorkflowService.TaskDef(
                id = transcribeId,
                type = "TRANSCRIBE", materialId = "mat-2",
                payload = "{}", priority = TaskPriority.TRANSCRIBE_HIGH,
                dependsOnIds = listOf(spawnId),
            ),
        )

        val wfId = CommonWorkflowService.createWorkflow(
            template = "ASR_PIPELINE",
            materialId = "mat-2",
            tasks = taskDefs,
        )

        val tasks = taskDb.listByWorkflowRun(wfId)
        assertEquals(3, tasks.size)

        val byId = tasks.associateBy { it.id }
        assertEquals(TaskPriority.TRANSCRIBE_MEDIUM, byId[extractId.value]!!.priority)
        assertEquals(TaskPriority.TRANSCRIBE_MEDIUM, byId[spawnId.value]!!.priority)
        assertEquals(TaskPriority.TRANSCRIBE_HIGH, byId[transcribeId.value]!!.priority)

        // 验证 dependsOn 也正确传播
        val transcribeTask = byId[transcribeId.value]!!
        assertTrue(transcribeTask.dependsOn?.contains(spawnId.value) == true,
            "TRANSCRIBE 应依赖 ASR_SPAWN_CHUNKS")
    }

    private fun assertTrue(condition: Boolean, message: String) {
        kotlin.test.assertTrue(condition, message)
    }
}
