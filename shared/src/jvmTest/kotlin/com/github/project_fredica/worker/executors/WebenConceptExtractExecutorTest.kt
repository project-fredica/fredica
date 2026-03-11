package com.github.project_fredica.worker.executors

// =============================================================================
// WebenConceptExtractExecutorTest —— 概念抽取 Executor 单元测试
// =============================================================================
//
// 测试范围：
//   1. chunkText 正常切块          — 文本按最大字符数切块，尊重句号边界
//   2. 空文本直接完成              — source_text.txt 为空时任务直接 completed
//   3. 概念写入 DB                 — FakePromptGraphRunner 注入预设概念，验证写入
//   4. 概念去重                    — 相同 canonicalName 不重复创建
//   5. 关系写入 DB                 — 验证 WebenRelation 正确写入
//   6. 闪卡写入 DB                 — 验证 WebenFlashcard 正确写入
//   7. analysisStatus 最终为 completed — WebenSource 状态联动
//   8. canSkip 检测                — concept_extract.done 存在时跳过
//
// =============================================================================

import com.github.project_fredica.api.routes.PromptGraphEngineService
import com.github.project_fredica.api.routes.PromptGraphRunner
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.json
import com.github.project_fredica.db.AppConfigDb
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskDb
import com.github.project_fredica.db.TaskService
import com.github.project_fredica.db.WorkflowRunDb
import com.github.project_fredica.db.WorkflowRunService
import com.github.project_fredica.db.promptgraph.PromptGraphDefDb
import com.github.project_fredica.db.promptgraph.PromptGraphDefService
import com.github.project_fredica.db.promptgraph.PromptGraphRun
import com.github.project_fredica.db.promptgraph.PromptGraphRunDb
import com.github.project_fredica.db.promptgraph.PromptGraphRunService
import com.github.project_fredica.db.promptgraph.PromptNodeRun
import com.github.project_fredica.db.promptgraph.PromptNodeRunDb
import com.github.project_fredica.db.promptgraph.PromptNodeRunService
import com.github.project_fredica.db.weben.WebenConceptDb
import com.github.project_fredica.db.weben.WebenConceptService
import com.github.project_fredica.db.weben.WebenFlashcardDb
import com.github.project_fredica.db.weben.WebenFlashcardService
import com.github.project_fredica.db.weben.WebenRelationDb
import com.github.project_fredica.db.weben.WebenRelationService
import com.github.project_fredica.db.weben.WebenSegmentDb
import com.github.project_fredica.db.weben.WebenSegmentService
import com.github.project_fredica.db.weben.WebenSource
import com.github.project_fredica.db.weben.WebenSourceDb
import com.github.project_fredica.db.weben.WebenSourceService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import java.util.UUID
import kotlin.test.*

class WebenConceptExtractExecutorTest {

    private lateinit var db: Database
    private lateinit var tmpDir: File

    // ── DB 实例 ────────────────────────────────────────────────────────────────

    private lateinit var conceptDb: WebenConceptDb
    private lateinit var relationDb: WebenRelationDb
    private lateinit var flashcardDb: WebenFlashcardDb
    private lateinit var segmentDb: WebenSegmentDb
    private lateinit var sourceDb: WebenSourceDb
    private lateinit var taskDb: TaskDb
    private lateinit var workflowRunDb: WorkflowRunDb
    private lateinit var pgDefDb: PromptGraphDefDb
    private lateinit var pgRunDb: PromptGraphRunDb
    private lateinit var pgNodeDb: PromptNodeRunDb

    // ── 测试固件 ───────────────────────────────────────────────────────────────

    private val nowSec get() = System.currentTimeMillis() / 1000L

    @BeforeTest
    fun setup() = runBlocking {
        val tmpFile = File.createTempFile("weben_executor_test_", ".db").also { it.deleteOnExit() }
        tmpDir = File.createTempFile("weben_test_dir_", "").also {
            it.delete()
            it.mkdirs()
            it.deleteOnExit()
        }
        db = Database.connect("jdbc:sqlite:${tmpFile.absolutePath}", "org.sqlite.JDBC")

        conceptDb    = WebenConceptDb(db)
        relationDb   = WebenRelationDb(db)
        flashcardDb  = WebenFlashcardDb(db)
        segmentDb    = WebenSegmentDb(db)
        sourceDb     = WebenSourceDb(db)
        taskDb       = TaskDb(db)
        workflowRunDb = WorkflowRunDb(db)
        pgDefDb      = PromptGraphDefDb(db)
        pgRunDb      = PromptGraphRunDb(db)
        pgNodeDb     = PromptNodeRunDb(db)

        val appConfigDb = AppConfigDb(db)
        appConfigDb.initialize()
        AppConfigService.initialize(appConfigDb)

        conceptDb.initialize()
        relationDb.initialize()
        flashcardDb.initialize()
        segmentDb.initialize()
        sourceDb.initialize()
        taskDb.initialize()
        workflowRunDb.initialize()
        pgDefDb.initialize()
        pgRunDb.initialize()
        pgNodeDb.initialize()

        WebenConceptService.initialize(conceptDb)
        WebenRelationService.initialize(relationDb)
        WebenFlashcardService.initialize(flashcardDb)
        WebenSegmentService.initialize(segmentDb)
        WebenSourceService.initialize(sourceDb)
        TaskService.initialize(taskDb)
        WorkflowRunService.initialize(workflowRunDb)
        PromptGraphDefService.initialize(pgDefDb)
        PromptGraphRunService.initialize(pgRunDb)
        PromptNodeRunService.initialize(pgNodeDb)
    }

    // ── FakePromptGraphRunner ─────────────────────────────────────────────────

    /**
     * 注入预设输出（概念/关系/闪卡 JSON），模拟 PromptGraphEngine 真实执行。
     *
     * @param conceptsJson   concept_extract 节点的 output_json
     * @param relationsJson  relation_extract 节点的 output_json
     * @param flashcardsJson flashcard_gen 节点的 output_json
     */
    private inner class FakePromptGraphRunner(
        private val conceptsJson: String,
        private val relationsJson: String = """{"relations":[]}""",
        private val flashcardsJson: String = """{"flashcards":[]}""",
    ) : PromptGraphRunner {

        override suspend fun run(
            defId: String,
            initialContext: Map<String, String>,
            materialId: String?,
            workflowRunId: String?,
            cancelSignal: CompletableDeferred<Unit>?,
        ): String {
            val runId = UUID.randomUUID().toString()
            // 写 PromptGraphRun
            pgRunDb.create(PromptGraphRun(
                id               = runId,
                promptGraphDefId = defId,
                graphDefVer      = 1,
                schemaVersion    = "1.0.0",
                status           = "completed",
                materialId       = materialId,
                workflowRunId    = workflowRunId,
                createdAt        = nowSec,
                completedAt      = nowSec,
            ))
            // 写三个节点输出
            writeNodeRun(runId, "concept_extract",  conceptsJson)
            writeNodeRun(runId, "relation_extract", relationsJson)
            writeNodeRun(runId, "flashcard_gen",    flashcardsJson)
            return runId
        }

        private suspend fun writeNodeRun(runId: String, nodeId: String, outputJson: String) {
            val nodeRunId = UUID.randomUUID().toString()
            pgNodeDb.create(PromptNodeRun(
                id               = nodeRunId,
                promptGraphRunId = runId,
                nodeDefId        = nodeId,
                status           = "completed",
                outputJson       = outputJson,
                createdAt        = nowSec,
                completedAt      = nowSec,
            ))
        }
    }

    // ── 工具方法 ───────────────────────────────────────────────────────────────

    private fun makeSource(id: String = UUID.randomUUID().toString(), bvid: String = "BV1test"): WebenSource {
        return WebenSource(
            id         = id,
            url        = "https://www.bilibili.com/video/$bvid",
            title      = "测试视频",
            sourceType = "bilibili_video",
            bvid       = bvid,
            createdAt  = nowSec,
        )
    }

    private fun makeTask(
        sourceId: String,
        textPath: String,
        materialId: String = "",
    ): Task {
        val payload = buildValidJson {
            kv("source_id",   sourceId)
            kv("text_path",   textPath)
            kv("video_title", "测试视频")
        }.str
        return Task(
            id            = UUID.randomUUID().toString(),
            type          = "WEBEN_CONCEPT_EXTRACT",
            workflowRunId = UUID.randomUUID().toString(),
            materialId    = materialId,
            payload       = payload,
            createdAt     = nowSec,
        )
    }

    // ── 辅助：覆盖 AppDataDir 到 tmpDir ──────────────────────────────────────

    // 由于 AppUtil.Paths.webenSourceDir() 依赖 appDataDir（lazy 初始化），
    // 测试中直接使用 tmpDir 下的子目录模拟 webenSourceDir。
    // 测试方法中手动构建路径，不依赖 AppUtil.Paths。

    private fun webenSourceDir(sourceId: String): File =
        tmpDir.resolve("weben").resolve(sourceId).also { it.mkdirs() }

    // ── Test 1: chunkText 正常切块 ────────────────────────────────────────────

    @Test
    fun `chunkText splits text at sentence boundary`() {
        // 访问 object 内私有 chunkText 方法：通过反射
        val method = WebenConceptExtractExecutor::class.java.getDeclaredMethod(
            "chunkText", String::class.java, Int::class.java
        )
        method.isAccessible = true

        val text = "这是第一句话。这是第二句话，稍长一些。这是第三句话！" +
                "A".repeat(1000) + "。这是最后一句。"

        @Suppress("UNCHECKED_CAST")
        val chunks = method.invoke(WebenConceptExtractExecutor, text, 50) as List<*>
        assertTrue(chunks.size >= 2, "应分割为至少 2 块，实际 ${chunks.size} 块")
    }

    // ── Test 2: 空文本直接完成 ────────────────────────────────────────────────

    @Test
    fun `empty text completes without calling PromptGraph`() = runBlocking {
        var calledRunner = false
        PromptGraphEngineService.initialize(object : PromptGraphRunner {
            override suspend fun run(
                defId: String, initialContext: Map<String, String>,
                materialId: String?, workflowRunId: String?,
                cancelSignal: CompletableDeferred<Unit>?,
            ): String { calledRunner = true; return "" }
        })

        val sourceId = UUID.randomUUID().toString()
        val source   = makeSource(id = sourceId)
        WebenSourceService.repo.create(source)

        val textFile = webenSourceDir(sourceId).resolve("source_text.txt")
        textFile.writeText("")

        val task = makeTask(sourceId, textFile.absolutePath)
        TaskService.repo.create(task)

        // 直接调用 executeWithSignals（通过 execute 触发）
        val result = WebenConceptExtractExecutor.execute(task)
        assertFalse(calledRunner, "空文本不应调用 PromptGraphRunner")
        assertNull(result.error, "空文本任务应成功: error=${result.error}")
    }

    // ── Test 3: 概念写入 DB ───────────────────────────────────────────────────

    @Test
    fun `concepts are written to DB after execution`() = runBlocking {
        val conceptsJson = """
            {"concepts":[
                {"name":"PWM","type":"术语","brief_definition":"脉冲宽度调制","aliases":["脉宽调制"],"timestamp_hints":[10.0]},
                {"name":"GPIO","type":"术语","brief_definition":"通用输入输出","aliases":[],"timestamp_hints":[]}
            ]}
        """.trimIndent()

        PromptGraphEngineService.initialize(FakePromptGraphRunner(conceptsJson = conceptsJson))

        val sourceId = UUID.randomUUID().toString()
        WebenSourceService.repo.create(makeSource(id = sourceId))

        val textFile = webenSourceDir(sourceId).resolve("source_text.txt")
        textFile.writeText("脉冲宽度调制（PWM）是一种重要的数字信号技术，常用于控制电机速度。GPIO 是微控制器上的通用 IO 口，可配置为输入或输出。")

        val task = makeTask(sourceId, textFile.absolutePath)
        TaskService.repo.create(task)

        val result = WebenConceptExtractExecutor.execute(task)
        assertNull(result.error, "执行应成功: error=${result.error}")

        val pwm  = WebenConceptService.repo.getByCanonicalName("pwm")
        val gpio = WebenConceptService.repo.getByCanonicalName("gpio")
        assertNotNull(pwm,  "PWM 应写入 DB")
        assertNotNull(gpio, "GPIO 应写入 DB")
        assertEquals("pwm",  pwm.canonicalName)
        assertEquals("gpio", gpio.canonicalName)
    }

    // ── Test 4: 概念去重 ──────────────────────────────────────────────────────

    @Test
    fun `duplicate concept canonical names are deduplicated`() = runBlocking {
        // 两个 chunk 均含 PWM，应只写入一条
        val conceptsJson = """{"concepts":[{"name":"PWM","type":"术语"}]}"""
        PromptGraphEngineService.initialize(FakePromptGraphRunner(conceptsJson = conceptsJson))

        val sourceId = UUID.randomUUID().toString()
        WebenSourceService.repo.create(makeSource(id = sourceId))

        // 构造足够长的文本使切块产生 2 块（每块 ≤ 1500 字符）
        val text = "PWM 是一种信号调制技术。" + "A".repeat(1600)
        val textFile = webenSourceDir(sourceId).resolve("source_text.txt")
        textFile.writeText(text)

        val task = makeTask(sourceId, textFile.absolutePath)
        TaskService.repo.create(task)

        WebenConceptExtractExecutor.execute(task)

        val all = WebenConceptService.repo.listAll()
        val pwmConcepts = all.filter { it.canonicalName == "pwm" }
        assertEquals(1, pwmConcepts.size, "相同 canonicalName 应去重，实际数量=${pwmConcepts.size}")
    }

    // ── Test 5: 关系写入 DB ───────────────────────────────────────────────────

    @Test
    fun `relations are written to DB`() = runBlocking {
        val conceptsJson = """{"concepts":[
            {"name":"PWM","type":"术语"},{"name":"Timer","type":"器件芯片"}
        ]}"""
        val relationsJson = """{"relations":[
            {"subject":"PWM","predicate":"依赖","object":"Timer","confidence":0.9}
        ]}"""
        PromptGraphEngineService.initialize(
            FakePromptGraphRunner(conceptsJson = conceptsJson, relationsJson = relationsJson)
        )

        val sourceId = UUID.randomUUID().toString()
        WebenSourceService.repo.create(makeSource(id = sourceId))
        val textFile = webenSourceDir(sourceId).resolve("source_text.txt")
        textFile.writeText("PWM 信号通过 Timer 外设产生，Timer 计数匹配触发输出翻转。")

        val task = makeTask(sourceId, textFile.absolutePath)
        TaskService.repo.create(task)

        WebenConceptExtractExecutor.execute(task)

        val pwm = WebenConceptService.repo.getByCanonicalName("pwm")
        assertNotNull(pwm)
        val relations = WebenRelationService.repo.listBySubject(pwm.id)
        assertEquals(1, relations.size, "应写入 1 条关系，实际=${relations.size}")
        assertEquals("依赖", relations.first().predicate)
    }

    // ── Test 6: 闪卡写入 DB ──────────────────────────────────────────────────

    @Test
    fun `flashcards are written to DB`() = runBlocking {
        val conceptsJson  = """{"concepts":[{"name":"GPIO","type":"术语"}]}"""
        val flashcardsJson = """{"flashcards":[
            {"concept_name":"GPIO","question":"GPIO 的全称是什么？","answer":"通用输入输出（General Purpose Input Output）","card_type":"qa"}
        ]}"""
        PromptGraphEngineService.initialize(
            FakePromptGraphRunner(conceptsJson = conceptsJson, flashcardsJson = flashcardsJson)
        )

        val sourceId = UUID.randomUUID().toString()
        WebenSourceService.repo.create(makeSource(id = sourceId))
        val textFile = webenSourceDir(sourceId).resolve("source_text.txt")
        textFile.writeText("GPIO 是通用输入输出接口，可配置为输入或输出模式。")

        val task = makeTask(sourceId, textFile.absolutePath)
        TaskService.repo.create(task)

        WebenConceptExtractExecutor.execute(task)

        val gpio = WebenConceptService.repo.getByCanonicalName("gpio")
        assertNotNull(gpio)
        val cards = WebenFlashcardService.repo.listByConcept(gpio.id)
        assertEquals(1, cards.size, "应写入 1 张闪卡，实际=${cards.size}")
        assertEquals("GPIO 的全称是什么？", cards.first().question)
    }

    // ── Test 7: analysisStatus 最终为 completed ───────────────────────────────

    @Test
    fun `source analysisStatus is completed after execution`() = runBlocking {
        val conceptsJson = """{"concepts":[{"name":"UART","type":"协议"}]}"""
        PromptGraphEngineService.initialize(FakePromptGraphRunner(conceptsJson = conceptsJson))

        val sourceId = UUID.randomUUID().toString()
        WebenSourceService.repo.create(makeSource(id = sourceId))
        val textFile = webenSourceDir(sourceId).resolve("source_text.txt")
        textFile.writeText("UART 是一种异步串行通信协议，使用 TX/RX 双线进行全双工通信。")

        val task = makeTask(sourceId, textFile.absolutePath)
        TaskService.repo.create(task)

        WebenConceptExtractExecutor.execute(task)

        val updated = WebenSourceService.repo.getById(sourceId)
        assertNotNull(updated)
        assertEquals("completed", updated.analysisStatus, "analysisStatus 应为 completed")
    }

    // ── Test 8: canSkip 检测 ──────────────────────────────────────────────────

    @Test
    fun `canSkip returns true when concept_extract done file exists`() {
        val sourceId = UUID.randomUUID().toString()
        val doneFile = webenSourceDir(sourceId).resolve("concept_extract.done")

        // 在 AppUtil.Paths 未指向 tmpDir 的测试环境中，canSkip 通过 AppUtil.Paths.webenSourceDir 查找。
        // 因此此测试只验证文件存在时逻辑：直接在 AppUtil.Paths.appDataDir 创建对应目录
        val realSourceDir = runBlocking { AppUtil.Paths.webenSourceDir(sourceId) }
        realSourceDir.resolve("concept_extract.done").writeText("ok")

        val payload = buildValidJson {
            kv("source_id",   sourceId)
            kv("text_path",   "/tmp/nonexistent.txt")
            kv("video_title", "test")
        }.str
        val task = Task(
            id            = UUID.randomUUID().toString(),
            type          = "WEBEN_CONCEPT_EXTRACT",
            workflowRunId = UUID.randomUUID().toString(),
            materialId    = "",
            payload       = payload,
            createdAt     = System.currentTimeMillis() / 1000L,
        )

        assertTrue(WebenConceptExtractExecutor.canSkip(task), "存在 concept_extract.done 时应 canSkip=true")
    }
}
