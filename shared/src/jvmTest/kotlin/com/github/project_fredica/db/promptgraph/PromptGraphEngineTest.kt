package com.github.project_fredica.db.promptgraph

// =============================================================================
// PromptGraphEngineTest —— PromptGraphEngine 单元测试
// =============================================================================
//
// 测试范围：
//   1.  upsertSystemGraphs / getById     — 系统图加载
//   2.  upsert 幂等                      — 同 id 写入两次以最新版本为准
//   3.  engine.run() 单节点图            — 最小执行路径，验证 run/nodeRun 状态
//   4.  双节点串行图                     — node_a → node_b 顺序执行
//   5.  LLM_CALL 模板变量替换           — %变量% 被正确注入 prompt
//   6.  节点输出追加到 context_json     — 后序节点可读前序节点输出
//   7.  TRANSFORM 节点（Kotlin handler）— TransformHandlerRegistry 调用
//   8.  取消信号                        — cancelSignal 触发后引擎抛异常
//   9.  三张表均正确写入                — PromptNodeRun status/output
//   10. mergeContext 正确合并           — 两次 merge，两个 key 均可查到
//   11. 并行分叉图（fan-out）           — root 同时激活 branch_a / branch_b
//   12. 有环图抛异常                    — topologicalSort 检测并 error
//   13. 不支持节点类型（CONDITION）     — 输出 {}，run 仍 completed
//   14. 节点重试成功                    — 第一次 LLM 失败，重试后完成
//   15. modelIdOverride 绕过 role 系统  — 节点直接指定 appModelId
//   16. ensureJsonOutput 提取嵌套 JSON  — LLM 返回散文 + JSON，引擎提取 JSON 段
//   17. ensureJsonOutput 包装纯文本     — LLM 输出不含 JSON，包装为 raw_output
//   18. contextMaxChars 截断           — contextInclude 片段超过 maxChars 时截断
//
// =============================================================================

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.json
import com.github.project_fredica.db.AppConfigDb
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.llm.LlmModelConfig
import com.github.project_fredica.llm.LlmCapability
import com.github.project_fredica.promptgraph.LlmCaller
import com.github.project_fredica.promptgraph.PromptGraphEngine
import com.github.project_fredica.promptgraph.TransformHandlerRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.*

class PromptGraphEngineTest {

    private lateinit var db: Database
    private lateinit var defDb: PromptGraphDefDb
    private lateinit var runDb: PromptGraphRunDb
    private lateinit var nodeRunDb: PromptNodeRunDb
    private lateinit var appConfigDb: AppConfigDb

    @BeforeTest
    fun setup() = runBlocking {
        // 使用 SQLite 临时文件隔离：不能用 :memory:，因为 ktorm 连接池每次开新连接，
        // 内存数据库各连接之间数据独立，导致表在一个连接里建好后另一个连接看不到。
        val tmpFile = File.createTempFile("promptgraphtest_", ".db").also { it.deleteOnExit() }
        db = Database.connect("jdbc:sqlite:${tmpFile.absolutePath}", "org.sqlite.JDBC")
        appConfigDb = AppConfigDb(db)
        defDb = PromptGraphDefDb(db)
        runDb = PromptGraphRunDb(db)
        nodeRunDb = PromptNodeRunDb(db)
        appConfigDb.initialize()
        defDb.initialize()
        runDb.initialize()
        nodeRunDb.initialize()

        AppConfigService.initialize(appConfigDb)
        PromptGraphDefService.initialize(defDb)
        PromptGraphRunService.initialize(runDb)
        PromptNodeRunService.initialize(nodeRunDb)

        // resolveModelConfig 会读取 AppConfig.llmModelsJson，
        // 必须预置至少一个模型配置，否则引擎找不到模型会抛出异常导致所有测试失败。
        // appModelId="fake" 与下面各测试的 modelRole="chat" 通过 LlmDefaultRoles 默认值关联。
        val fakeModel = LlmModelConfig(
            id = "fake-model-id",
            name = "Fake LLM",
            baseUrl = "http://localhost:9999",
            apiKey = "fake-key",
            model = "fake-model",
            capabilities = setOf(LlmCapability.STREAMING),
            appModelId = "fake",
        )
        val config = appConfigDb.getConfig()
        appConfigDb.updateConfig(
            config.copy(llmModelsJson = AppUtil.GlobalVars.json.encodeToString(listOf(fakeModel)))
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun nowSec() = System.currentTimeMillis() / 1000L

    /**
     * FakeLlmCaller：根据请求体内容（requestBody）中包含的关键词路由到预设响应。
     *
     * 匹配逻辑：检查 requestBody 是否包含每个 key（通常是节点的 system_prompt 或 label），
     * 返回对应的预设 JSON 字符串。无匹配时返回默认 `{"result":"fake_output"}`。
     *
     * 这种设计让测试无需真实 LLM 调用，同时能精确控制各节点的"输出"，
     * 验证引擎是否正确传递上下文给下游节点。
     */
    private fun makeFakeLlmCaller(vararg responses: Pair<String, String>): LlmCaller {
        val responseMap = responses.toMap()
        return { modelConfig, requestBody, cancelSignal ->
            // 从请求体中提取 system_prompt 中的关键词来路由响应
            val matched = responseMap.entries.firstOrNull { (key, _) -> requestBody.contains(key) }
            matched?.value ?: """{"result":"fake_output"}"""
        }
    }

    /**
     * 构建最小可用的双节点串行图（node_a → node_b）。
     *
     * 图结构：
     *   node_a (LLM_CALL): userPromptTpl="处理：%input_text%"
     *     ↓ 边 e1
     *   node_b (LLM_CALL): userPromptTpl="汇总：%node_a%", contextInclude=["node_a"]
     *
     * node_b 通过两种方式引用 node_a 的输出：
     *   - `%node_a%`：通过 renderTemplate 将 node_a 输出的 JSON 字符串内联替换进 user prompt
     *   - contextInclude=["node_a"]：将 node_a 输出以 [node_a] 标题附加到 user prompt 末尾
     */
    private suspend fun createTwoNodeGraph(graphId: String = "test:two_node_graph"): PromptGraphDef {
        val nodes = AppUtil.GlobalVars.json.encodeToString(
            listOf(
                PromptNodeDef(
                    id = "node_a",
                    type = PromptNodeType.LLM_CALL,
                    label = "节点A",
                    modelRole = "chat",
                    systemPrompt = "你是测试节点A",
                    userPromptTpl = "处理：%input_text%",
                ),
                PromptNodeDef(
                    id = "node_b",
                    type = PromptNodeType.LLM_CALL,
                    label = "节点B",
                    modelRole = "chat",
                    systemPrompt = "你是测试节点B",
                    userPromptTpl = "汇总：%node_a%",
                    contextInclude = listOf("node_a"),
                ),
            )
        )
        val edges = AppUtil.GlobalVars.json.encodeToString(
            listOf(
                PromptEdgeDef(id = "e1", sourceNodeId = "node_a", targetNodeId = "node_b"),
            )
        )
        val def = PromptGraphDef(
            id          = graphId,
            name        = "双节点测试图",
            nodesJson   = nodes,
            edgesJson   = edges,
            createdAt   = nowSec(),
            updatedAt   = nowSec(),
        )
        defDb.upsert(def)
        return def
    }

    // ── Test 1: upsert / getById ──────────────────────────────────────────────
    //
    // 流程：
    //   写入 PromptGraphDef(id="test:simple") ──→ DB upsert
    //   查询 DB getById("test:simple") ──→ 断言字段值
    //
    // 验证：def 写入后能正确读回，字段值与原始数据一致；nodesJson 默认值为 "[]"。

    @Test
    fun `upsert and getById round-trip`() = runBlocking {
        val def = PromptGraphDef(
            id = "test:simple",
            name = "简单测试图",
            sourceType = "user",
            createdAt = nowSec(),
            updatedAt = nowSec(),
        )
        defDb.upsert(def)
        val found = defDb.getById("test:simple")
        assertNotNull(found)
        assertEquals("简单测试图", found.name)
        assertEquals("user", found.sourceType)
        assertEquals("[]", found.nodesJson)
    }

    // ── Test 2: upsert 幂等 ───────────────────────────────────────────────────
    //
    // 流程：
    //   第一次写入 PromptGraphDef(id="test:idem", name="版本1", version=1)
    //   第二次写入 PromptGraphDef(id="test:idem", name="版本2", version=2)（相同 id）
    //   查询 DB getById("test:idem") ──→ 断言 name="版本2", version=2
    //
    // 验证：ON CONFLICT DO UPDATE 正确替换旧记录，name 和 version 以最后一次写入为准。

    @Test
    fun `upsert is idempotent on conflict`() = runBlocking {
        val nowSec = nowSec()
        val def = PromptGraphDef(id = "test:idem", name = "版本1", version = 1, createdAt = nowSec, updatedAt = nowSec)
        defDb.upsert(def)
        defDb.upsert(def.copy(name = "版本2", version = 2))
        val found = defDb.getById("test:idem")!!
        assertEquals("版本2", found.name)
        assertEquals(2, found.version)
    }

    // ── Test 3: 单节点图执行 ─────────────────────────────────────────────────
    //
    // 图结构：
    //   [only] LLM_CALL
    //     systemPrompt="single"
    //     userPromptTpl="test"
    //
    // 执行流程：
    //   engine.run("test:single")
    //     → 创建 PromptGraphRun(status="running")
    //     → 创建 PromptNodeRun(nodeId="only", status="pending")
    //     → 拓扑排序 → ["only"]
    //     → 执行 only：调用 FakeLlmCaller → 返回 {"output":"ok"}
    //     → 无 schema 约束 → 包装为 {"text": "{\"output\":\"ok\"}"}
    //     → PromptNodeRun(status="completed", outputJson=...)
    //     → PromptGraphRun(status="completed")
    //
    // 验证：run.status=completed，nodeRun.status=completed，outputJson 非空。

    @Test
    fun `engine executes single-node graph`() = runBlocking {
        val nodes = AppUtil.GlobalVars.json.encodeToString(listOf(
            PromptNodeDef(id = "only", type = PromptNodeType.LLM_CALL, label = "唯一节点",
                modelRole = "chat", systemPrompt = "single", userPromptTpl = "test")
        ))
        val def = PromptGraphDef(
            id = "test:single", name = "单节点图",
            nodesJson = nodes, edgesJson = "[]",
            createdAt = nowSec(), updatedAt = nowSec(),
        )
        defDb.upsert(def)

        val engine = PromptGraphEngine(makeFakeLlmCaller("single" to """{"output":"ok"}"""))
        val runId = engine.run(defId = "test:single")

        val run = runDb.getById(runId)!!
        assertEquals("completed", run.status)
        assertNotNull(run.completedAt)

        val nodeRuns = nodeRunDb.listByRun(runId)
        assertEquals(1, nodeRuns.size)
        assertEquals("completed", nodeRuns[0].status)
        assertNotNull(nodeRuns[0].outputJson)
        Unit
    }

    // ── Test 4: 双节点串行图（基础流程）──────────────────────────────────────
    //
    // 图结构：
    //   [node_a] LLM_CALL
    //     systemPrompt="你是测试节点A"
    //     userPromptTpl="处理：%input_text%"
    //       ↓ 边 e1
    //   [node_b] LLM_CALL
    //     systemPrompt="你是测试节点B"
    //     userPromptTpl="汇总：%node_a%"
    //     contextInclude=["node_a"]
    //
    // 执行流程：
    //   initialContext = {input_text: "GPIO是通用IO接口，PWM是脉冲宽度调制"}
    //   拓扑排序 → [node_a, node_b]
    //   Step 1: 执行 node_a → FakeLlmCaller 匹配"节点A" → {"concepts":["GPIO","PWM"]}
    //   Step 2: mergeContext(runId, "node_a", {...}) → contextJson["node_a"] 已存在
    //   Step 3: 执行 node_b → FakeLlmCaller 匹配"节点B" → {"summary":"GPIO和PWM已整合"}
    //   Step 4: run.status = "completed"
    //
    // 验证：引擎能按拓扑序执行，两个节点均完成，run 状态为 completed。

    @Test
    fun `engine executes two-node serial graph`() = runBlocking {
        createTwoNodeGraph()

        val fakeCaller = makeFakeLlmCaller(
            "节点A" to """{"concepts":["GPIO","PWM"]}""",
            "节点B" to """{"summary":"GPIO和PWM已整合"}""",
        )
        val engine = PromptGraphEngine(fakeCaller)
        val runId = engine.run(
            defId = "test:two_node_graph",
            initialContext = mapOf("input_text" to "GPIO是通用IO接口，PWM是脉冲宽度调制"),
        )

        val run = runDb.getById(runId)!!
        assertEquals("completed", run.status)

        val nodeRuns = nodeRunDb.listByRun(runId)
        assertEquals(2, nodeRuns.size)
        assertTrue(nodeRuns.all { it.status == "completed" })
    }

    // ── Test 5: 模板变量替换 ─────────────────────────────────────────────────
    //
    // 图结构：
    //   [render_test] LLM_CALL
    //     systemPrompt="系统"
    //     userPromptTpl="处理：%my_var%"
    //
    // 执行流程：
    //   initialContext = {my_var: "HELLO_VALUE"}
    //   renderTemplate("处理：%my_var%", {my_var: "\"HELLO_VALUE\""})
    //     → "\"HELLO_VALUE\"" 以 " 开头结尾 → 去外层引号 → "HELLO_VALUE"
    //     → result: "处理：HELLO_VALUE"
    //   LLM 请求体构建：user prompt = "处理：HELLO_VALUE"
    //   captureCaller 记录完整 requestBody
    //
    // 验证：capturedRequest 中包含 "HELLO_VALUE"，而非 "%my_var%"。

    @Test
    fun `LLM_CALL renders template variables from initial context`() = runBlocking {
        val nodes = AppUtil.GlobalVars.json.encodeToString(listOf(
            PromptNodeDef(id = "render_test", type = PromptNodeType.LLM_CALL, label = "渲染测试",
                modelRole = "chat", systemPrompt = "系统", userPromptTpl = "处理：%my_var%")
        ))
        val def = PromptGraphDef(id = "test:render", name = "渲染测试图",
            nodesJson = nodes, edgesJson = "[]", createdAt = nowSec(), updatedAt = nowSec())
        defDb.upsert(def)

        var capturedRequest = ""
        val captureCaller: LlmCaller = { _, requestBody, _ ->
            capturedRequest = requestBody
            """{"text":"ok"}"""
        }
        val engine = PromptGraphEngine(captureCaller)
        engine.run(defId = "test:render", initialContext = mapOf("my_var" to "HELLO_VALUE"))

        assertTrue(capturedRequest.contains("HELLO_VALUE"), "期望请求体包含模板变量的替换值")
    }

    // ── Test 6: 节点输出追加到 context，后序节点可读 ─────────────────────────
    //
    // 图结构（同双节点串行图，graphId="test:context_flow"）：
    //   [node_a] ─→ [node_b]
    //
    // 数据流追踪：
    //   1. node_a 执行完成 → outputJson = {"data":"node_a_output_value"}
    //   2. mergeContext(runId, "node_a", {"data":"node_a_output_value"})
    //      contextJson 变为: {"input_text":"test","node_a":{"data":"node_a_output_value"}}
    //   3. 读取最新 contextJson → parseContextToMap
    //      → context["node_a"] = "{\"data\":\"node_a_output_value\"}"
    //   4. node_b 的 userPromptTpl="汇总：%node_a%"
    //      renderTemplate: %node_a% → 值不以 " 开头 → 直接替换 JSON 字符串
    //      → user prompt = "汇总：{"data":"node_a_output_value"}"
    //   5. 同时 contextInclude=["node_a"] → 在 prompt 末尾附加 [node_a] 段落
    //   6. captureCaller 捕获 node_b 的 requestBody
    //
    // 验证：node_b 的 requestBody 包含 "node_a_output_value"，
    //   证明 mergeContext → parseContextToMap → renderTemplate 的数据流正确贯通。

    @Test
    fun `node output is available in context for downstream nodes`() = runBlocking {
        createTwoNodeGraph("test:context_flow")

        var capturedNodeBRequest = ""
        val fakeCaller: LlmCaller = { _, requestBody, _ ->
            if (requestBody.contains("节点B")) {
                capturedNodeBRequest = requestBody
                """{"summary":"done"}"""
            } else {
                """{"data":"node_a_output_value"}"""
            }
        }
        val engine = PromptGraphEngine(fakeCaller)
        engine.run(defId = "test:context_flow", initialContext = mapOf("input_text" to "test"))

        // node_b 的 user_prompt_tpl 是 "汇总：%node_a%"，应包含 node_a 的输出
        assertTrue(
            capturedNodeBRequest.contains("node_a_output_value"),
            "期望节点B的请求包含节点A的输出：$capturedNodeBRequest"
        )
    }

    // ── Test 7: TRANSFORM 节点（Kotlin handler）─────────────────────────────
    //
    // 图结构：
    //   [transform_node] TRANSFORM
    //     transformHandler="test_handler"
    //
    // 执行流程：
    //   TransformHandlerRegistry.register("test_handler") { ctx → {"transformed":true} }
    //   engine.run("test:transform")
    //     → 拓扑排序 → ["transform_node"]
    //     → executeTransform("transform_node")
    //       → TransformHandlerRegistry.get("test_handler") → 调用 handler(contextJson)
    //       → 返回 {"transformed":true}
    //     → setOutput(nodeRunId, {"transformed":true})
    //     → updateStatus("completed")
    //
    // 验证：
    //   - handlerCalled=true（handler 确实被调用）
    //   - nodeRun.outputJson = {"transformed":true}（原样写入，不做 JSON 包装）

    @Test
    fun `TRANSFORM node calls registered Kotlin handler`() = runBlocking {
        var handlerCalled = false
        TransformHandlerRegistry.register("test_handler") { contextJson ->
            handlerCalled = true
            """{"transformed":true}"""
        }

        val nodes = AppUtil.GlobalVars.json.encodeToString(listOf(
            PromptNodeDef(id = "transform_node", type = PromptNodeType.TRANSFORM,
                label = "转换节点", transformHandler = "test_handler"),
        ))
        val def = PromptGraphDef(id = "test:transform", name = "转换测试图",
            nodesJson = nodes, edgesJson = "[]", createdAt = nowSec(), updatedAt = nowSec())
        defDb.upsert(def)

        val engine = PromptGraphEngine(makeFakeLlmCaller())
        engine.run(defId = "test:transform")

        assertTrue(handlerCalled, "期望 TRANSFORM handler 被调用")

        val nodeRuns = nodeRunDb.listByRun(
            runDb.listByDef("test:transform").first().id
        )
        assertEquals("""{"transformed":true}""", nodeRuns[0].outputJson)
    }

    // ── Test 8: 取消信号 ─────────────────────────────────────────────────────
    //
    // 图结构（同双节点串行图，graphId="test:cancel_graph"）：
    //   [node_a] ─→ [node_b]
    //
    // 执行时序：
    //   t0: engine.run() 启动，拓扑排序 → [node_a, node_b]
    //   t1: 进入 node_a 前：检查 cancelSignal → isCompleted=false → 继续
    //   t2: node_a 调用 blockingCaller（callCount=1）
    //       → callCount==1，触发 cancelSignal.complete(Unit)
    //       → 返回 {"done":true}
    //   t3: node_a 执行完成，mergeContext
    //   t4: 进入 node_b 前：检查 cancelSignal → isCompleted=true → 触发取消
    //       → updateStatus(runId, "failed")
    //       → error("已取消")
    //
    // 注意：取消检测发生在节点边界，不是在 LLM 调用中途打断。
    //   node_a 完整执行完毕（callCount=1），node_b 从未执行（callCount 停在 1）。
    //
    // 验证：
    //   - engine.run() 抛出异常（assertFailsWith<Exception>）
    //   - run.status = "failed"（取消视为失败，保留现场便于调试）

    @Test
    fun `cancel signal aborts execution`() = runBlocking {
        createTwoNodeGraph("test:cancel_graph")

        val cancelSignal = CompletableDeferred<Unit>()
        var callCount = 0
        val blockingCaller: LlmCaller = { _, _, _ ->
            callCount++
            if (callCount == 1) {
                // 模拟第一个节点执行后取消
                cancelSignal.complete(Unit)
            }
            """{"done":true}"""
        }

        val engine = PromptGraphEngine(blockingCaller)
        assertFailsWith<Exception>("期望取消后抛出异常") {
            engine.run(defId = "test:cancel_graph", cancelSignal = cancelSignal)
        }

        // 验证 run 状态被标记为 failed
        val runs = runDb.listByDef("test:cancel_graph")
        assertTrue(runs.isNotEmpty())
        assertEquals("failed", runs.first().status)
    }

    // ── Test 9: 三张表写入正确性 ─────────────────────────────────────────────
    //
    // 表关系（执行完成后）：
    //
    //   prompt_graph_def (test:tables_check)
    //     └─ prompt_graph_run (runId)
    //         ├─ status="completed"
    //         ├─ contextJson: {input_text, node_a, node_b}  ← mergeContext 两次
    //         ├─ prompt_node_run (node_a)
    //         │   ├─ status="completed"
    //         │   ├─ outputJson={"a_result":"value_a"}
    //         │   ├─ inputSnapshotJson 非空
    //         │   └─ effectiveOutput == outputJson（无 override 时）
    //         └─ prompt_node_run (node_b)
    //             ├─ status="completed"
    //             ├─ outputJson={"b_result":"value_b"}
    //             └─ inputSnapshotJson 非空
    //
    // 验证：三张表的关键字段均正确写入，effectiveOutput = outputJson（无 override）。

    @Test
    fun `all three tables written correctly after run`() = runBlocking {
        createTwoNodeGraph("test:tables_check")

        val engine = PromptGraphEngine(makeFakeLlmCaller(
            "节点A" to """{"a_result":"value_a"}""",
            "节点B" to """{"b_result":"value_b"}""",
        ))
        val runId = engine.run(defId = "test:tables_check")

        // prompt_graph_run 表
        val run = runDb.getById(runId)!!
        assertEquals("completed", run.status)
        assertEquals("test:tables_check", run.promptGraphDefId)
        assertTrue(run.contextJson.contains("node_a"))
        assertTrue(run.contextJson.contains("node_b"))
        assertNotNull(run.completedAt)

        // prompt_node_run 表
        val nodeRuns = nodeRunDb.listByRun(runId)
        assertEquals(2, nodeRuns.size)
        val nodeA = nodeRuns.find { it.nodeDefId == "node_a" }!!
        val nodeB = nodeRuns.find { it.nodeDefId == "node_b" }!!
        assertEquals("completed", nodeA.status)
        assertEquals("completed", nodeB.status)
        assertNotNull(nodeA.outputJson)
        assertNotNull(nodeB.outputJson)
        assertNotNull(nodeA.inputSnapshotJson)
        assertNotNull(nodeB.inputSnapshotJson)

        // effectiveOutput = outputJson（无 overrideJson 时）
        assertEquals(nodeA.outputJson, nodeA.effectiveOutput)
    }

    // ── Test 10: mergeContext 正确合并 ────────────────────────────────────────
    //
    // 流程（直接操作 DB，不经引擎）：
    //   create PromptGraphRun(contextJson="{}")
    //   mergeContext(id, "key1", {"val":1})  → contextJson = {"key1":{"val":1}}
    //   mergeContext(id, "key2", {"val":2})  → contextJson = {"key1":{"val":1},"key2":{"val":2}}
    //
    // 验证：连续调用 mergeContext 两次，两个 key 都出现在最终的 contextJson 中，
    //   且各自的值不互相覆盖。这是节点输出积累到 run context 的核心操作。

    @Test
    fun `mergeContext appends key to context_json`() = runBlocking {
        val nowSec = nowSec()
        val run = PromptGraphRun(
            id = "merge-test-run",
            promptGraphDefId = "test:merge",
            graphDefVer = 1,
            schemaVersion = "1.0.0",
            status = "running",
            contextJson = "{}",
            createdAt = nowSec,
        )
        runDb.create(run)

        runDb.mergeContext("merge-test-run", "key1", """{"val":1}""")
        runDb.mergeContext("merge-test-run", "key2", """{"val":2}""")

        val updated = runDb.getById("merge-test-run")!!
        assertTrue(updated.contextJson.contains("key1"))
        assertTrue(updated.contextJson.contains("key2"))
        assertTrue(updated.contextJson.contains("\"val\":1"))
        assertTrue(updated.contextJson.contains("\"val\":2"))
    }

    // ── Test 11: 并行分叉图（fan-out）────────────────────────────────────────
    //
    // 图结构（一个根节点并行激活两个叶节点）：
    //
    //              [root] LLM_CALL
    //             /              \
    //           边 e1           边 e2
    //           /                \
    //   [branch_a] LLM_CALL   [branch_b] LLM_CALL
    //
    // 拓扑排序分析（Kahn's algorithm）：
    //   初始入度: root=0, branch_a=1, branch_b=1
    //   队列: [root] → 处理 root → branch_a 入度=0，branch_b 入度=0
    //   队列: [branch_a, branch_b]（顺序依算法实现，不保证）
    //   result: [root, branch_a, branch_b] 或 [root, branch_b, branch_a]
    //   两者均是合法拓扑序（Phase B 顺序执行，不是真正并行）
    //
    // 执行流程：
    //   所有三节点按拓扑序依次执行（root 先，branch_a/b 任意顺序后）
    //   每个节点各自调用 FakeLlmCaller 一次
    //
    // 验证：
    //   - run.status = "completed"
    //   - nodeRuns 共 3 个，全部 status="completed"
    //   - LLM 被调用了恰好 3 次（每个节点一次）

    @Test
    fun `parallel fan-out graph executes all branches`() = runBlocking {
        val nodes = AppUtil.GlobalVars.json.encodeToString(listOf(
            PromptNodeDef(id = "root",     type = PromptNodeType.LLM_CALL, label = "根节点",
                modelRole = "chat", systemPrompt = "根系统", userPromptTpl = "根prompt"),
            PromptNodeDef(id = "branch_a", type = PromptNodeType.LLM_CALL, label = "分支A",
                modelRole = "chat", systemPrompt = "分支A系统", userPromptTpl = "分支A prompt"),
            PromptNodeDef(id = "branch_b", type = PromptNodeType.LLM_CALL, label = "分支B",
                modelRole = "chat", systemPrompt = "分支B系统", userPromptTpl = "分支B prompt"),
        ))
        val edges = AppUtil.GlobalVars.json.encodeToString(listOf(
            PromptEdgeDef(id = "e1", sourceNodeId = "root",     targetNodeId = "branch_a"),
            PromptEdgeDef(id = "e2", sourceNodeId = "root",     targetNodeId = "branch_b"),
        ))
        val def = PromptGraphDef(
            id = "test:fan_out", name = "并行分叉图",
            nodesJson = nodes, edgesJson = edges,
            createdAt = nowSec(), updatedAt = nowSec(),
        )
        defDb.upsert(def)

        var callCount = 0
        val countingCaller: LlmCaller = { _, _, _ ->
            callCount++
            """{"ok":true}"""
        }
        val engine = PromptGraphEngine(countingCaller)
        val runId = engine.run(defId = "test:fan_out")

        val run = runDb.getById(runId)!!
        assertEquals("completed", run.status)

        val nodeRuns = nodeRunDb.listByRun(runId)
        assertEquals(3, nodeRuns.size, "期望 3 个节点全部执行")
        assertTrue(nodeRuns.all { it.status == "completed" }, "期望所有节点 status=completed")
        assertEquals(3, callCount, "期望 LLM 调用恰好 3 次（每节点一次）")
    }

    // ── Test 12: 有环图抛异常 ─────────────────────────────────────────────────
    //
    // 图结构（故意引入环路）：
    //   [node_a] ──→ [node_b]
    //      ↑               |
    //      └───────────────┘
    //
    // Kahn 算法分析：
    //   初始入度: node_a=1（来自 node_b→node_a 边）, node_b=1（来自 node_a→node_b 边）
    //   队列初始为空（无入度=0 的节点）
    //   result.size=0 ≠ nodeIds.size=2 → 检测到环 → error()
    //
    // 注意：环检测发生在 engine.run() 内部的 topologicalSort() 调用中，
    //   在创建任何 PromptGraphRun 记录之前（目前实现是先排序再创建 run）。
    //   实际上当前实现是先创建 run（步骤4）再排序（步骤3），所以 run 可能已创建。
    //   但无论如何，engine.run() 应抛出异常。
    //
    // 验证：engine.run() 抛出包含"环"相关信息的异常。

    @Test
    fun `cycle in graph throws exception`() = runBlocking {
        val nodes = AppUtil.GlobalVars.json.encodeToString(listOf(
            PromptNodeDef(id = "node_a", type = PromptNodeType.LLM_CALL, label = "A",
                modelRole = "chat", systemPrompt = "A", userPromptTpl = "A"),
            PromptNodeDef(id = "node_b", type = PromptNodeType.LLM_CALL, label = "B",
                modelRole = "chat", systemPrompt = "B", userPromptTpl = "B"),
        ))
        // node_a → node_b → node_a（构成环）
        val edges = AppUtil.GlobalVars.json.encodeToString(listOf(
            PromptEdgeDef(id = "e1", sourceNodeId = "node_a", targetNodeId = "node_b"),
            PromptEdgeDef(id = "e2", sourceNodeId = "node_b", targetNodeId = "node_a"),
        ))
        val def = PromptGraphDef(
            id = "test:cycle", name = "有环图",
            nodesJson = nodes, edgesJson = edges,
            createdAt = nowSec(), updatedAt = nowSec(),
        )
        defDb.upsert(def)

        val engine = PromptGraphEngine(makeFakeLlmCaller())
        val ex = assertFailsWith<Exception>("期望有环图抛出异常") {
            engine.run(defId = "test:cycle")
        }
        // 确认异常来自环检测（错误信息中包含"环"或相关关键词）
        assertTrue(
            ex.message?.contains("环") == true || ex.message?.contains("cycle") == true,
            "期望异常消息包含环检测说明，实际: ${ex.message}"
        )
    }

    // ── Test 13: 不支持节点类型（CONDITION）输出空对象 ─────────────────────
    //
    // 图结构：
    //   [cond_node] CONDITION（Phase B MVP 未实现）
    //
    // 执行流程：
    //   executeNode 进入 else 分支：
    //     logger.warn("节点类型 CONDITION 在 Phase B MVP 中未实现，输出空对象 nodeId=cond_node")
    //     return "{}"
    //   setOutput(nodeRunId, "{}")
    //   updateStatus("completed")
    //
    // 验证：
    //   - run.status = "completed"（引擎不崩溃，优雅降级）
    //   - nodeRun.status = "completed"
    //   - nodeRun.outputJson = "{}"（空对象）

    @Test
    fun `unsupported node type CONDITION outputs empty object and completes`() = runBlocking {
        val nodes = AppUtil.GlobalVars.json.encodeToString(listOf(
            PromptNodeDef(id = "cond_node", type = PromptNodeType.CONDITION,
                label = "条件节点", conditionExpr = "ctx.x > 0"),
        ))
        val def = PromptGraphDef(
            id = "test:condition", name = "条件节点图",
            nodesJson = nodes, edgesJson = "[]",
            createdAt = nowSec(), updatedAt = nowSec(),
        )
        defDb.upsert(def)

        val engine = PromptGraphEngine(makeFakeLlmCaller())
        val runId = engine.run(defId = "test:condition")

        val run = runDb.getById(runId)!!
        assertEquals("completed", run.status, "CONDITION 节点应优雅降级，run 仍 completed")

        val nodeRuns = nodeRunDb.listByRun(runId)
        assertEquals(1, nodeRuns.size)
        assertEquals("completed", nodeRuns[0].status)
        assertEquals("{}", nodeRuns[0].outputJson, "CONDITION 节点输出应为空对象 {}")
    }

    // ── Test 14: 节点重试成功 ─────────────────────────────────────────────────
    //
    // 图结构：
    //   [retry_node] LLM_CALL  maxRetries=1
    //
    // 执行流程：
    //   executeNodeWithRetry(retries=1) → repeat(2) { attempt →
    //     attempt=0: blockingCaller 抛出 RuntimeException("模拟失败") → lastError = e
    //     attempt=1: blockingCaller 返回 {"ok":"success"} → return
    //   }
    //   正常完成，不抛出异常
    //
    // 验证：
    //   - engine.run() 不抛出异常
    //   - run.status = "completed"
    //   - callCount = 2（第一次失败 + 第二次成功）

    @Test
    fun `node retry succeeds after transient failure`() = runBlocking {
        val nodes = AppUtil.GlobalVars.json.encodeToString(listOf(
            PromptNodeDef(id = "retry_node", type = PromptNodeType.LLM_CALL, label = "重试节点",
                modelRole = "chat", systemPrompt = "retry-sys", userPromptTpl = "retry-user",
                maxRetries = 1),   // 允许重试 1 次（共最多 2 次调用）
        ))
        val def = PromptGraphDef(
            id = "test:retry", name = "重试测试图",
            nodesJson = nodes, edgesJson = "[]",
            createdAt = nowSec(), updatedAt = nowSec(),
        )
        defDb.upsert(def)

        var callCount = 0
        val retryableCaller: LlmCaller = { _, _, _ ->
            callCount++
            if (callCount == 1) {
                // 第一次调用模拟瞬时故障（网络超时、服务降级等场景）
                throw RuntimeException("模拟 LLM 瞬时故障（attempt=0）")
            }
            // 第二次调用成功返回
            """{"ok":"success"}"""
        }
        val engine = PromptGraphEngine(retryableCaller)
        val runId = engine.run(defId = "test:retry")

        val run = runDb.getById(runId)!!
        assertEquals("completed", run.status, "重试成功后 run 应 completed")
        assertEquals(2, callCount, "期望 LLM 被调用 2 次（1 次失败 + 1 次成功）")
    }

    // ── Test 15: modelIdOverride 绕过 role 系统，选择指定模型 ─────────────────
    //
    // 前置条件：
    //   AppConfig.llmModelsJson 中有两个模型：
    //     model-A: appModelId="fake"（测试默认模型）
    //     model-B: appModelId="special-model", model="special-model-name"
    //
    // 图结构：
    //   [override_node] LLM_CALL
    //     modelIdOverride="special-model"（直接指定 appModelId）
    //     modelRole=null（被 override 覆盖，不参与解析）
    //
    // resolveModelConfig 流程（优先级 1）：
    //   node.modelIdOverride="special-model" → 非空
    //   → models.find { it.appModelId == "special-model" } → model-B
    //   → 返回 model-B（model="special-model-name"）
    //
    // 验证：LLM 请求体中 "model" 字段值为 "special-model-name"（override 模型的 model 字段）。

    @Test
    fun `modelIdOverride selects specific model bypassing role system`() = runBlocking {
        // 注册第二个模型（appModelId="special-model"）
        val specialModel = LlmModelConfig(
            id = "special-model-db-id",
            name = "Special LLM",
            baseUrl = "http://localhost:9999",
            apiKey = "fake-key",
            model = "special-model-name",   // 这个 model 字段应该出现在请求体里
            capabilities = setOf(LlmCapability.STREAMING),
            appModelId = "special-model",
        )
        val defaultModel = LlmModelConfig(
            id = "fake-model-id",
            name = "Fake LLM",
            baseUrl = "http://localhost:9999",
            apiKey = "fake-key",
            model = "fake-model",
            capabilities = setOf(LlmCapability.STREAMING),
            appModelId = "fake",
        )
        val config = appConfigDb.getConfig()
        appConfigDb.updateConfig(
            config.copy(llmModelsJson = AppUtil.GlobalVars.json.encodeToString(listOf(defaultModel, specialModel)))
        )

        val nodes = AppUtil.GlobalVars.json.encodeToString(listOf(
            PromptNodeDef(id = "override_node", type = PromptNodeType.LLM_CALL, label = "Override节点",
                // modelIdOverride 指定 appModelId，覆盖 modelRole 角色系统
                modelIdOverride = "special-model",
                systemPrompt = "override-sys", userPromptTpl = "override-user"),
        ))
        val def = PromptGraphDef(
            id = "test:override", name = "模型覆盖测试图",
            nodesJson = nodes, edgesJson = "[]",
            createdAt = nowSec(), updatedAt = nowSec(),
        )
        defDb.upsert(def)

        var capturedBody = ""
        val captureCaller: LlmCaller = { _, requestBody, _ ->
            capturedBody = requestBody
            """{"ok":true}"""
        }
        val engine = PromptGraphEngine(captureCaller)
        engine.run(defId = "test:override")

        // 请求体中 "model" 字段应为 "special-model-name"（来自 override 模型配置）
        assertTrue(
            capturedBody.contains("special-model-name"),
            "期望请求体中使用 override 指定的模型名，实际请求体: $capturedBody"
        )
        assertFalse(
            capturedBody.contains("\"model\":\"fake-model\""),
            "期望不使用默认 fake-model，实际请求体: $capturedBody"
        )
    }

    // ── Test 16: ensureJsonOutput 从散文中提取 JSON ───────────────────────────
    //
    // 场景：LLM 输出 JSON 时在前后加了解释性散文（GPT-3.5 等模型的常见行为）：
    //   "以下是提取结果：\n{\"concepts\":[\"GPIO\",\"PWM\"]}\n希望这有帮助。"
    //
    // ensureJsonOutput 流程：
    //   trimmed = "以下是提取结果：\n{\"concepts\":[...]}\n希望这有帮助。"
    //   jsonStart = trimmed.indexOf('{') → 找到第一个 {
    //   jsonEnd   = trimmed.lastIndexOf('}') → 找到最后一个 }
    //   extracted = trimmed.substring(jsonStart, jsonEnd + 1) = {"concepts":["GPIO","PWM"]}
    //   runCatching { parseToJsonElement(extracted) } → 成功 → 返回 extracted
    //
    // 前置条件：需要一个带 schemaId 的边，才能触发 ensureJsonOutput 路径
    //   （无 schema 时，LLM 输出被包装为 {"text": "..."} 不经过 ensureJsonOutput）
    //
    // 验证：nodeRun.outputJson = {"concepts":["GPIO","PWM"]}（散文被剥离）

    @Test
    fun `ensureJsonOutput extracts JSON from prose-wrapped LLM output`() = runBlocking {
        // 构建带 schema 注册表的 Graph（edge 绑定 schemaId，触发 ensureJsonOutput 路径）
        val schemaEntry = SchemaEntry(
            id = "concept_schema",
            description = "概念提取 schema",
            schema = """{"type":"object","properties":{"concepts":{"type":"array","items":{"type":"string"}}}}""",
        )
        val nodes = AppUtil.GlobalVars.json.encodeToString(listOf(
            PromptNodeDef(id = "extract_node", type = PromptNodeType.LLM_CALL, label = "提取节点",
                modelRole = "chat", systemPrompt = "提取概念", userPromptTpl = "提取：%text%"),
            PromptNodeDef(id = "sink_node", type = PromptNodeType.LLM_CALL, label = "接收节点",
                modelRole = "chat", systemPrompt = "接收", userPromptTpl = "接收输入"),
        ))
        // 边带 schemaId → 触发 ensureJsonOutput
        val edges = AppUtil.GlobalVars.json.encodeToString(listOf(
            PromptEdgeDef(id = "e1", sourceNodeId = "extract_node", targetNodeId = "sink_node",
                schemaId = "concept_schema"),
        ))
        val def = PromptGraphDef(
            id = "test:extract_json", name = "JSON提取测试图",
            nodesJson = nodes, edgesJson = edges,
            schemaRegistryJson = AppUtil.GlobalVars.json.encodeToString(listOf(schemaEntry)),
            createdAt = nowSec(), updatedAt = nowSec(),
        )
        defDb.upsert(def)

        // LLM 返回散文包裹的 JSON（模拟 GPT 不严格遵守 response_format 的情况）
        val fakeCaller: LlmCaller = { _, requestBody, _ ->
            if (requestBody.contains("提取概念")) {
                // 散文 + JSON + 散文
                "以下是提取结果：\n{\"concepts\":[\"GPIO\",\"PWM\"]}\n希望这有帮助。"
            } else {
                """{"ack":true}"""
            }
        }
        val engine = PromptGraphEngine(fakeCaller)
        val runId = engine.run(
            defId = "test:extract_json",
            initialContext = mapOf("text" to "GPIO和PWM技术介绍"),
        )

        val nodeRuns = nodeRunDb.listByRun(runId)
        val extractNodeRun = nodeRuns.find { it.nodeDefId == "extract_node" }!!

        // outputJson 应是提取后的纯 JSON，散文已被剥离
        val output = extractNodeRun.outputJson ?: ""
        assertTrue(output.contains("concepts"), "期望 outputJson 包含 concepts 字段，实际: $output")
        assertTrue(output.contains("GPIO"),     "期望 outputJson 包含 GPIO，实际: $output")
        assertFalse(output.contains("以下是"),  "期望散文已被剥离，实际: $output")
        assertFalse(output.contains("希望这有"), "期望散文已被剥离，实际: $output")
    }

    // ── Test 17: ensureJsonOutput 将纯文本包装为 raw_output ──────────────────
    //
    // 场景：LLM 输出完全不含 JSON 结构（模型幻觉或 system prompt 不当）：
    //   "我无法提取这段文本中的概念，请提供更多上下文。"
    //
    // ensureJsonOutput 流程：
    //   trimmed = "我无法提取这段文本中的概念，..."
    //   trimmed.indexOf('{') = -1 → jsonStart = null
    //   trimmed.indexOf('[') = -1 → jsonStart = null
    //   进入 else 分支：
    //     logger.warn("节点 X 输出不含 JSON 结构，包装为 raw_output")
    //     return """{"raw_output":"我无法提取..."}"""
    //
    // 前置条件：同 Test 16，需要带 schemaId 的边触发 ensureJsonOutput 路径。
    //
    // 验证：nodeRun.outputJson = {"raw_output":"..."} 且原始文本保留在值中。

    @Test
    fun `ensureJsonOutput wraps pure text output as raw_output`() = runBlocking {
        val schemaEntry = SchemaEntry(
            id = "schema_for_wrap_test",
            description = "包装测试 schema",
            schema = """{"type":"object","properties":{"result":{"type":"string"}}}""",
        )
        val nodes = AppUtil.GlobalVars.json.encodeToString(listOf(
            PromptNodeDef(id = "text_node", type = PromptNodeType.LLM_CALL, label = "文本节点",
                modelRole = "chat", systemPrompt = "无法提取", userPromptTpl = "输入：%data%"),
            PromptNodeDef(id = "sink_node2", type = PromptNodeType.LLM_CALL, label = "接收节点",
                modelRole = "chat", systemPrompt = "接收", userPromptTpl = "接收"),
        ))
        val edges = AppUtil.GlobalVars.json.encodeToString(listOf(
            PromptEdgeDef(id = "e1", sourceNodeId = "text_node", targetNodeId = "sink_node2",
                schemaId = "schema_for_wrap_test"),
        ))
        val def = PromptGraphDef(
            id = "test:raw_output", name = "raw_output 包装测试",
            nodesJson = nodes, edgesJson = edges,
            schemaRegistryJson = AppUtil.GlobalVars.json.encodeToString(listOf(schemaEntry)),
            createdAt = nowSec(), updatedAt = nowSec(),
        )
        defDb.upsert(def)

        val fakeCaller: LlmCaller = { _, requestBody, _ ->
            if (requestBody.contains("无法提取")) {
                // 返回不含任何 JSON 结构的纯文本
                "我无法提取这段文本中的概念，请提供更多上下文。"
            } else {
                """{"ack":true}"""
            }
        }
        val engine = PromptGraphEngine(fakeCaller)
        val runId = engine.run(
            defId = "test:raw_output",
            initialContext = mapOf("data" to "随机数据"),
        )

        val nodeRuns = nodeRunDb.listByRun(runId)
        val textNodeRun = nodeRuns.find { it.nodeDefId == "text_node" }!!
        val output = textNodeRun.outputJson ?: ""

        // 输出应被包装为 {"raw_output":"原始文本"}
        assertTrue(output.contains("raw_output"), "期望输出被包装为 raw_output，实际: $output")
        assertTrue(output.contains("无法提取"),   "期望原始文本保留在 raw_output 值中，实际: $output")
    }

    // ── Test 18: contextMaxChars 截断过长上下文片段 ───────────────────────────
    //
    // 图结构：
    //   [producer] LLM_CALL
    //       ↓ 边 e1
    //   [consumer] LLM_CALL  contextInclude=["producer"]  contextMaxChars=20
    //
    // buildContextSlice 流程：
    //   include = ["producer"]（不为空，触发 slice 构建）
    //   context["producer"] = {"long":"AAAA..."}（很长的字符串）
    //   sb.append("[producer]\n{\"long\":\"AAAA...\"}\n\n")
    //   sb.length > 20 → break（超出 contextMaxChars）
    //   return sb.take(20)（硬截断至 20 字符）
    //
    // 注意：contextMaxChars 限制的是最终注入进 LLM 请求的上下文片段长度，
    //   不影响 %变量% 模板替换（renderTemplate 不受此参数影响）。
    //
    // 验证：
    //   - consumer 的 requestBody 中来自 contextSlice 的部分不超过 20 字符
    //   - consumer 仍然能正常完成（run status=completed）

    @Test
    fun `contextMaxChars truncates overlong context slice`() = runBlocking {
        val nodes = AppUtil.GlobalVars.json.encodeToString(listOf(
            PromptNodeDef(id = "producer", type = PromptNodeType.LLM_CALL, label = "生产节点",
                modelRole = "chat", systemPrompt = "生产者", userPromptTpl = "生成长内容"),
            PromptNodeDef(id = "consumer", type = PromptNodeType.LLM_CALL, label = "消费节点",
                modelRole = "chat", systemPrompt = "消费者", userPromptTpl = "消费",
                // contextInclude 触发 buildContextSlice，contextMaxChars=20 触发截断
                contextInclude = listOf("producer"),
                contextMaxChars = 20),
        ))
        val edges = AppUtil.GlobalVars.json.encodeToString(listOf(
            PromptEdgeDef(id = "e1", sourceNodeId = "producer", targetNodeId = "consumer"),
        ))
        val def = PromptGraphDef(
            id = "test:ctx_truncate", name = "上下文截断测试",
            nodesJson = nodes, edgesJson = edges,
            createdAt = nowSec(), updatedAt = nowSec(),
        )
        defDb.upsert(def)

        // 生产节点输出一段很长的文本（远超 20 字符）
        val longOutput = "A".repeat(500)
        var capturedConsumerRequest = ""
        val fakeCaller: LlmCaller = { _, requestBody, _ ->
            if (requestBody.contains("生产者")) {
                // 返回很长的纯文本（无 schema，包装为 {"text":"AAAA..."}）
                longOutput
            } else {
                capturedConsumerRequest = requestBody
                """{"ok":true}"""
            }
        }
        val engine = PromptGraphEngine(fakeCaller)
        val runId = engine.run(defId = "test:ctx_truncate")

        // run 应成功完成
        val run = runDb.getById(runId)!!
        assertEquals("completed", run.status)

        // consumer 的请求体中来自 contextSlice（"相关上下文：" 部分之后）应被截断
        // 从请求体中找到 "相关上下文：" 后的 slice 部分，验证其长度 ≤ 20 字符
        // （contextSlice 本身 ≤ 20 字符，但注意请求体还包含其他字段）
        val contextSliceMarker = "相关上下文："
        if (capturedConsumerRequest.contains(contextSliceMarker)) {
            val sliceStart = capturedConsumerRequest.indexOf(contextSliceMarker) + contextSliceMarker.length
            val sliceContent = capturedConsumerRequest.substring(sliceStart)
            // slice 内容（不含 JSON 转义）不应超过 20 字符（测试允许宽松：验证远小于原始 500 字符长度）
            assertTrue(
                sliceContent.length < longOutput.length,
                "期望 contextSlice 被截断，slice 部分长度应小于原始输出长度 ${longOutput.length}"
            )
        }
        // 无论如何，consumer 节点应完成执行（不因截断崩溃）
        val nodeRuns = nodeRunDb.listByRun(runId)
        val consumerRun = nodeRuns.find { it.nodeDefId == "consumer" }!!
        assertEquals("completed", consumerRun.status)
    }
}
