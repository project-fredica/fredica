package com.github.project_fredica.db

// =============================================================================
// RestartTaskLogDbTest —— restart_task_log 表的单元测试
// =============================================================================
//
// 测试范围：
//   1. recordRestartSession    — 正确写入快照，字段逐一断言
//   2. recordRestartSession（supersede）— 第二次重启自动将旧条目标记为 superseded
//   3. countPendingReview      — 写入后计数正确，dismiss 后减少
//   4. updateDisposition byIds — 按 IDs 批量更新 disposition
//   5. updateDisposition bySession — 按 session_id 批量更新 disposition
//   6. updateDisposition recreated — new_workflow_run_id 正确写入
//   7. testSnapshotNonTerminalTasks（在 TaskDbTest 中追加，此处引用说明）
// =============================================================================

import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestartTaskLogDbTest {

    private lateinit var db: Database
    private lateinit var taskDb: TaskDb
    private lateinit var workflowRunDb: WorkflowRunDb
    private lateinit var logDb: RestartTaskLogDb

    @BeforeTest
    fun setup() = runBlocking {
        val tmpFile = File.createTempFile("restartlogdbtest_", ".db").also { it.deleteOnExit() }
        db = Database.connect(
            url    = "jdbc:sqlite:${tmpFile.absolutePath}",
            driver = "org.sqlite.JDBC",
        )
        workflowRunDb = WorkflowRunDb(db)
        taskDb        = TaskDb(db)
        logDb         = RestartTaskLogDb(db)

        workflowRunDb.initialize()
        taskDb.initialize()
        logDb.initialize()

        TaskService.initialize(taskDb)
        WorkflowRunService.initialize(workflowRunDb)
        RestartTaskLogService.initialize(logDb)

        // 创建测试用 WorkflowRun 和 Task
        workflowRunDb.create(WorkflowRun(
            id = "wr-1", materialId = "mat-1", template = "TEST",
            status = "pending", totalTasks = 0, doneTasks = 0, createdAt = nowSec(),
        ))
    }

    // ── 测试 1：recordRestartSession 基本写入 ──────────────────────────────────

    @Test
    fun testRecordRestartSession() = runBlocking {
        val tasks = listOf(
            makeTask("t-running", "DOWNLOAD_VIDEO", "wr-1", "mat-1", "running",  payload = """{"bvid":"BV1"}""", progress = 42),
            makeTask("t-claimed", "TRANSCODE_MP4",  "wr-1", "mat-1", "claimed",  payload = """{"file":"x.mp4"}""", progress = 0),
            makeTask("t-pending", "FETCH_SUBTITLE", "wr-1", "mat-1", "pending",  payload = "{}",                  progress = 0),
        )
        taskDb.createAll(tasks)

        val sessionId = "session-abc"
        val nowSec = nowSec()
        logDb.recordRestartSession(sessionId, tasks, nowSec)

        val logs = logDb.listAll()
        assertEquals(3, logs.size, "应写入 3 条日志")

        val running = logs.find { it.taskId == "t-running" }!!
        assertEquals(sessionId,              running.sessionId,        "sessionId 字段应正确")
        assertEquals("DOWNLOAD_VIDEO",       running.taskType,         "task_type 字段应正确")
        assertEquals("wr-1",                 running.workflowRunId,    "workflow_run_id 字段应正确")
        assertEquals("mat-1",                running.materialId,       "material_id 字段应正确")
        assertEquals("running",              running.statusAtRestart,  "status_at_restart 应为 running")
        assertEquals("""{"bvid":"BV1"}""",   running.payload,          "payload 字段应正确")
        assertEquals(42,                     running.progress,         "progress 字段应正确")
        assertEquals("pending_review",       running.disposition,      "默认 disposition 应为 pending_review")
        assertEquals(nowSec,                 running.createdAt,        "created_at 应等于传入的 nowSec")
        assertNull(running.resolvedAt,                                  "新记录 resolved_at 应为 null")

        val claimed = logs.find { it.taskId == "t-claimed" }!!
        assertEquals("claimed", claimed.statusAtRestart, "status_at_restart 应为 claimed")

        val pending = logs.find { it.taskId == "t-pending" }!!
        assertEquals("pending", pending.statusAtRestart, "status_at_restart 应为 pending")
    }

    // ── 测试 2：第二次重启自动 supersede 旧记录 ───────────────────────────────

    @Test
    fun testRecordRestartSession_supersedesOldPendingReview() = runBlocking {
        val task1 = makeTask("t1", "DOWNLOAD_VIDEO", "wr-1", "mat-1", "running")
        taskDb.create(task1)

        // 第一次重启
        logDb.recordRestartSession("session-1", listOf(task1), nowSec())
        val afterFirst = logDb.listAll()
        assertEquals(1, afterFirst.size)
        assertEquals("pending_review", afterFirst[0].disposition, "第一次重启后应为 pending_review")

        // 模拟用户未处置，第二次重启
        val task2 = makeTask("t2", "TRANSCODE_MP4", "wr-1", "mat-1", "running")
        taskDb.create(task2)
        logDb.recordRestartSession("session-2", listOf(task2), nowSec())

        val afterSecond = logDb.listAll()
        assertEquals(2, afterSecond.size)

        val oldLog = afterSecond.find { it.sessionId == "session-1" }!!
        assertEquals("superseded", oldLog.disposition, "第一次重启的记录应被自动标记为 superseded")
        assertNotNull(oldLog.resolvedAt, "superseded 时应填写 resolved_at")

        val newLog = afterSecond.find { it.sessionId == "session-2" }!!
        assertEquals("pending_review", newLog.disposition, "第二次重启的记录应为 pending_review")
    }

    // ── 测试 3：countPendingReview ────────────────────────────────────────────

    @Test
    fun testCountPendingReview() = runBlocking {
        val tasks = listOf(
            makeTask("tc1", "DOWNLOAD_VIDEO", "wr-1", "mat-1", "running"),
            makeTask("tc2", "TRANSCODE_MP4",  "wr-1", "mat-1", "pending"),
            makeTask("tc3", "FETCH_SUBTITLE", "wr-1", "mat-1", "claimed"),
        )
        taskDb.createAll(tasks)
        logDb.recordRestartSession("session-count", tasks, nowSec())

        assertEquals(3, logDb.countPendingReview(), "写入 3 条后 pending_review 数量应为 3")

        // dismiss 第一条
        val logs = logDb.listAll(disposition = "pending_review")
        logDb.updateDisposition(ids = listOf(logs[0].id), disposition = "dismissed")

        assertEquals(2, logDb.countPendingReview(), "dismiss 一条后 pending_review 数量应减为 2")
    }

    // ── 测试 4：updateDisposition byIds ──────────────────────────────────────

    @Test
    fun testUpdateDisposition_byIds() = runBlocking {
        val tasks = listOf(
            makeTask("td1", "DOWNLOAD_VIDEO", "wr-1", "mat-1", "running"),
            makeTask("td2", "TRANSCODE_MP4",  "wr-1", "mat-1", "pending"),
        )
        taskDb.createAll(tasks)
        logDb.recordRestartSession("session-byids", tasks, nowSec())

        val logs = logDb.listAll(disposition = "pending_review")
        val targetIds = listOf(logs[0].id, logs[1].id)

        logDb.updateDisposition(ids = targetIds, disposition = "dismissed")

        val updated = logDb.listAll(disposition = "dismissed")
        assertEquals(2, updated.size, "应有 2 条记录变为 dismissed")
        updated.forEach { log ->
            assertEquals("dismissed", log.disposition, "disposition 应为 dismissed")
            assertNotNull(log.resolvedAt, "resolved_at 应已填写")
        }
        assertEquals(0, logDb.countPendingReview(), "pending_review 应为 0")
    }

    // ── 测试 5：updateDisposition bySession ──────────────────────────────────

    @Test
    fun testUpdateDisposition_bySession() = runBlocking {
        val tasks = listOf(
            makeTask("ts1", "DOWNLOAD_VIDEO", "wr-1", "mat-1", "running"),
            makeTask("ts2", "TRANSCODE_MP4",  "wr-1", "mat-1", "pending"),
        )
        taskDb.createAll(tasks)
        logDb.recordRestartSession("session-bysession", tasks, nowSec())

        logDb.updateDisposition(sessionId = "session-bysession", disposition = "dismissed")

        val dismissed = logDb.listAll(disposition = "dismissed")
        assertEquals(2, dismissed.size, "session 内所有记录应变为 dismissed")
        assertEquals(0, logDb.countPendingReview(), "pending_review 应为 0")
    }

    // ── 测试 6：updateDisposition recreated ──────────────────────────────────

    @Test
    fun testUpdateDisposition_recreated() = runBlocking {
        val task = makeTask("tr1", "DOWNLOAD_VIDEO", "wr-1", "mat-1", "running")
        taskDb.create(task)
        logDb.recordRestartSession("session-recreate", listOf(task), nowSec())

        val logs = logDb.listAll(disposition = "pending_review")
        val logId = logs[0].id
        val newWfId = "wr-new-123"

        logDb.updateDisposition(ids = listOf(logId), disposition = "dismissed", newWorkflowRunId = newWfId)

        val updated = logDb.listAll(disposition = "recreated")
        assertEquals(1, updated.size, "应有 1 条变为 recreated")
        assertEquals("recreated", updated[0].disposition,       "disposition 应为 recreated")
        assertEquals(newWfId,     updated[0].newWorkflowRunId,  "new_workflow_run_id 应正确写入")
        assertNotNull(updated[0].resolvedAt, "resolved_at 应已填写")
        Unit
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────────────

    private fun nowSec() = System.currentTimeMillis() / 1000L

    private fun makeTask(
        id: String,
        type: String,
        workflowRunId: String,
        materialId: String,
        status: String,
        payload: String = "{}",
        progress: Int = 0,
    ) = Task(
        id            = id,
        type          = type,
        workflowRunId = workflowRunId,
        materialId    = materialId,
        status        = status,
        payload       = payload,
        progress      = progress,
        createdAt     = nowSec(),
    )
}
