package com.github.project_fredica.promptgraph

// =============================================================================
// PromptGraphEngine —— 提示词图 DAG 执行器
// =============================================================================
//
// Phase B MVP 实现范围：
//   - LLM_CALL 节点：模板变量替换 → LLM 调用 → 输出写 context
//   - TRANSFORM 节点：调用 TransformHandlerRegistry 中注册的 Kotlin 处理函数
//   - 节点 DAG 拓扑排序（Kahn's algorithm）
//   - 取消信号传递（CompletableDeferred）
//   - 完整的 input_snapshot_json 记录（便于调试/复现）
//
// 不在 MVP 范围内：CONDITION 节点、HUMAN_REVIEW 节点、MCP、schema 迁移、
//   并行节点并发执行（当前为顺序执行）、完整 JSON Schema Draft 7 校验。
//
// 设计重点：通过 [LlmCaller] 函数类型注入 LLM 调用实现，使引擎本身可测试
//（测试时传入 FakeLlmCaller，不依赖真实网络）。
// =============================================================================

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.error
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.db.promptgraph.PromptEdgeDef
import com.github.project_fredica.db.promptgraph.PromptGraphDefService
import com.github.project_fredica.db.promptgraph.PromptGraphRun
import com.github.project_fredica.db.promptgraph.PromptGraphRunService
import com.github.project_fredica.db.promptgraph.PromptNodeDef
import com.github.project_fredica.db.promptgraph.PromptNodeRun
import com.github.project_fredica.db.promptgraph.PromptNodeRunService
import com.github.project_fredica.db.promptgraph.PromptNodeType
import com.github.project_fredica.db.promptgraph.SchemaEntry
import com.github.project_fredica.llm.LlmDefaultRoles
import com.github.project_fredica.llm.LlmModelConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import kotlin.collections.iterator

// =============================================================================
// LlmCaller 函数类型（依赖注入，支持测试替换）
// =============================================================================

/**
 * LLM 调用函数类型。
 *
 * @param modelConfig  模型配置
 * @param requestBody  完整请求 JSON 字符串
 * @param cancelSignal 取消信号；完成后调用方应中断
 * @return LLM 完整输出字符串；取消时返回 null
 */
typealias LlmCaller = suspend (
    modelConfig: LlmModelConfig,
    requestBody: String,
    cancelSignal: CompletableDeferred<Unit>?,
) -> String?

// =============================================================================
// TransformHandlerRegistry（TRANSFORM 节点 Kotlin 处理函数注册表）
// =============================================================================

/**
 * TRANSFORM 节点的 Kotlin 处理函数注册表。
 *
 * Phase B MVP 注意：本注册表替代 prompt-graph.md §2.3 中描述的 JS 表达式引擎。
 * 每个 TRANSFORM 节点的 [PromptNodeDef.transformHandler] 引用此表中注册的函数 key。
 * 后续阶段引入 GraalVM/QuickJS 后，引擎将优先调用 JS 表达式，此表作为兜底。
 *
 * 处理函数签名：`suspend (contextJson: String) -> String`
 *   - 输入：当前运行的完整 context_json 字符串
 *   - 输出：此节点的输出 JSON 字符串（将写入 output_json 并合并到 context）
 */
object TransformHandlerRegistry {
    private val handlers = mutableMapOf<String, suspend (contextJson: String) -> String>()

    fun register(key: String, handler: suspend (contextJson: String) -> String) {
        handlers[key] = handler
    }

    fun get(key: String): (suspend (contextJson: String) -> String)? = handlers[key]
}

// =============================================================================
// PromptGraphEngine
// =============================================================================

/**
 * 提示词图 DAG 执行器。
 *
 * 每次调用 [run] 创建一个新的 [PromptGraphRun]，在单个协程中按拓扑序执行节点。
 * 引擎本身无状态，可重复调用。
 */
class PromptGraphEngine(
    /** LLM 调用实现（测试时注入 FakeLlmCaller）。 */
    private val llmCaller: LlmCaller,
) {
    private val logger = createLogger()

    /**
     * 启动一个 PromptGraphRun 并同步执行至完成（或取消/失败）。
     *
     * @param defId          PromptGraphDef.id
     * @param initialContext 初始 context JSON 对象（输入数据，如 chunk_text、video_context）
     * @param materialId     关联素材 ID（可选）
     * @param workflowRunId  所属 WorkflowRun.id（可选）
     * @param cancelSignal   取消信号；完成后引擎在下一个节点前检测并停止
     * @return 完成的 PromptGraphRun.id；失败/取消时抛出异常
     */
    suspend fun run(
        defId: String,
        initialContext: Map<String, String> = emptyMap(),
        materialId: String? = null,
        workflowRunId: String? = null,
        cancelSignal: CompletableDeferred<Unit>? = null,
    ): String = withContext(Dispatchers.IO) {
        val nowSec = System.currentTimeMillis() / 1000L

        // 步骤 1：加载 Graph 定义
        val def = PromptGraphDefService.repo.getById(defId)
            ?: error("PromptGraphEngine: Graph 定义未找到 id=$defId")

        logger.info("PromptGraphEngine.run: 开始执行 defId=$defId name=${def.name} version=${def.version}")

        // 步骤 2：解析节点和边（存储在 def 的 *_json 字段中）
        val nodes = def.nodesJson.loadJsonModel<List<PromptNodeDef>>()
            .getOrElse { e -> error("PromptGraphEngine: nodes_json 解析失败 defId=$defId: ${e.message}") }
        val edges = def.edgesJson.loadJsonModel<List<PromptEdgeDef>>()
            .getOrElse { e -> error("PromptGraphEngine: edges_json 解析失败 defId=$defId: ${e.message}") }
        // schema_registry_json 解析失败不中断执行（LLM 节点在无 schema 时降级为纯文本模式）
        val schemaRegistry = def.schemaRegistryJson.loadJsonModel<List<SchemaEntry>>()
            .getOrElse { emptyList() }

        if (nodes.isEmpty()) {
            error("PromptGraphEngine: Graph 无节点 defId=$defId")
        }

        // 步骤 3：拓扑排序，确定节点执行顺序
        // nodeMap 用于后续按 id 快速查找节点定义，避免在 sortedNodeIds 循环中遍历 nodes 列表
        val nodeMap = nodes.associateBy { it.id }
        val sortedNodeIds = topologicalSort(nodes, edges)
        logger.debug("PromptGraphEngine: 拓扑排序完成 order=${sortedNodeIds.joinToString()}")

        // 步骤 4：创建 PromptGraphRun 记录（status=running）
        // initialContextJson 将 Map<String, String> 序列化为 {"key":"value",...} 形式，
        // 作为图执行的初始 context，各节点通过 %key% 模板从中读取输入数据。
        val runId = UUID.randomUUID().toString()
        val initialContextJson = buildInitialContextJson(initialContext)
        val run = PromptGraphRun(
            id                = runId,
            promptGraphDefId  = defId,
            graphDefVer       = def.version,      // 快照版本，与后续升级解耦
            schemaVersion     = def.schemaVersion,
            workflowRunId     = workflowRunId,
            materialId        = materialId,
            status            = "running",
            contextJson       = initialContextJson,
            createdAt         = nowSec,
        )
        PromptGraphRunService.repo.create(run)

        // 步骤 5：为每个节点预创建 PromptNodeRun（status=pending）
        // 先批量建好所有节点记录，是为了让前端在执行过程中就能看到完整节点列表及其状态。
        for (node in nodes) {
            PromptNodeRunService.repo.create(
                PromptNodeRun(
                    id               = UUID.randomUUID().toString(),
                    promptGraphRunId = runId,
                    nodeDefId        = node.id,
                    status           = "pending",
                    createdAt        = nowSec,
                )
            )
        }

        // 步骤 6：按拓扑序顺序执行每个节点
        // Phase B MVP 为单线程顺序执行；并行执行（对无依赖节点）留待后续阶段。
        try {
            for (nodeId in sortedNodeIds) {
                // 每次进入新节点前检查取消信号，实现粗粒度取消
                // （不在 LLM 调用中间打断，而是在节点边界处检测）
                if (cancelSignal?.isCompleted == true) {
                    logger.info("PromptGraphEngine: 取消信号触发，runId=$runId nodeId=$nodeId")
                    PromptGraphRunService.repo.updateStatus(runId, "failed")
                    error("PromptGraphEngine: 已取消 runId=$runId")
                }

                val node = nodeMap[nodeId] ?: error("节点未找到 id=$nodeId")
                val nodeRun = PromptNodeRunService.repo.getByRunAndNode(runId, nodeId)
                    ?: error("PromptNodeRun 未找到 runId=$runId nodeId=$nodeId")

                logger.debug("PromptGraphEngine: 执行节点 nodeId=$nodeId type=${node.type} label=${node.label}")

                // 标记节点为 running（让前端可以显示哪个节点正在执行）
                PromptNodeRunService.repo.updateStatus(nodeRun.id, "running")

                // 每次执行节点前重新从 DB 读取 context，
                // 确保能读到前序节点刚合并进去的输出（mergeContext 是原地更新）
                val currentRun = PromptGraphRunService.repo.getById(runId)!!
                val contextJson = currentRun.contextJson

                // 执行节点（含重试，重试次数由 node.maxRetries 控制，默认为 0）
                val outputJson = executeNodeWithRetry(
                    node = node,
                    nodeRunId = nodeRun.id,
                    contextJson = contextJson,
                    edges = edges,
                    schemaRegistry = schemaRegistry,
                    cancelSignal = cancelSignal,
                    retries = node.maxRetries,
                )

                val nowEnd = System.currentTimeMillis() / 1000L

                // 写入节点输出（output_json 永不修改，作为历史快照）
                PromptNodeRunService.repo.setOutput(nodeRun.id, outputJson)
                PromptNodeRunService.repo.updateStatus(nodeRun.id, "completed", nowEnd)

                // 将此节点的输出以 nodeId 为 key 追加到 run 的 context_json，
                // 使后序节点可通过 %nodeId% 模板引用此节点的输出
                PromptGraphRunService.repo.mergeContext(runId, nodeId, outputJson)

                logger.debug("PromptGraphEngine: 节点完成 nodeId=$nodeId outputLen=${outputJson.length}")
            }

            // 步骤 7：所有节点成功完成，标记 run 为 completed
            val nowEnd = System.currentTimeMillis() / 1000L
            PromptGraphRunService.repo.updateStatus(runId, "completed", nowEnd)
            logger.info("PromptGraphEngine: 运行完成 runId=$runId")
            runId
        } catch (e: Exception) {
            // 任意节点失败（含取消）均将 run 标记为 failed，并重新抛出
            logger.error("PromptGraphEngine: 运行失败 runId=$runId", e)
            PromptGraphRunService.repo.updateStatus(runId, "failed")
            throw e
        }
    }

    // ── 私有实现 ─────────────────────────────────────────────────────────────

    /**
     * 执行单个节点（含重试逻辑）。
     *
     * @return 节点的输出 JSON 字符串
     */
    private suspend fun executeNodeWithRetry(
        node: PromptNodeDef,
        nodeRunId: String,
        contextJson: String,
        edges: List<PromptEdgeDef>,
        schemaRegistry: List<SchemaEntry>,
        cancelSignal: CompletableDeferred<Unit>?,
        retries: Int,
    ): String {
        var lastError: Exception? = null
        repeat(retries + 1) { attempt ->
            if (attempt > 0) {
                logger.warn("PromptGraphEngine: 节点重试 nodeId=${node.id} attempt=$attempt")
            }
            try {
                return executeNode(node, nodeRunId, contextJson, edges, schemaRegistry, cancelSignal)
            } catch (e: Exception) {
                lastError = e
                logger.error("PromptGraphEngine: 节点执行失败 nodeId=${node.id} attempt=$attempt", e)
            }
        }
        throw lastError ?: RuntimeException("PromptGraphEngine: 节点执行失败 nodeId=${node.id}")
    }

    /**
     * 执行单个节点（单次尝试）。
     */
    private suspend fun executeNode(
        node: PromptNodeDef,
        nodeRunId: String,
        contextJson: String,
        edges: List<PromptEdgeDef>,
        schemaRegistry: List<SchemaEntry>,
        cancelSignal: CompletableDeferred<Unit>?,
    ): String = when (node.type) {
        PromptNodeType.LLM_CALL  -> executeLlmCall(node, nodeRunId, contextJson, edges, schemaRegistry, cancelSignal)
        PromptNodeType.TRANSFORM -> executeTransform(node, nodeRunId, contextJson)
        else -> {
            // CONDITION、MERGE、HUMAN_REVIEW 在 Phase B MVP 中不执行，输出空对象
            logger.warn("PromptGraphEngine: 节点类型 ${node.type} 在 Phase B MVP 中未实现，输出空对象 nodeId=${node.id}")
            "{}"
        }
    }

    /**
     * 执行 LLM_CALL 节点。
     *
     * 流程：
     * 1. 确定模型配置（model_role → AppConfig.llmDefaultRoles → 模型列表查找）
     * 2. 确定出边 schema（用于 response_format 注入）
     * 3. 渲染提示词模板（%变量% 替换）
     * 4. 构建 input_snapshot 并存储
     * 5. 调用 LlmCaller
     * 6. 返回输出（JSON 字符串）
     */
    private suspend fun executeLlmCall(
        node: PromptNodeDef,
        nodeRunId: String,
        contextJson: String,
        edges: List<PromptEdgeDef>,
        schemaRegistry: List<SchemaEntry>,
        cancelSignal: CompletableDeferred<Unit>?,
    ): String {
        // 1. 解析 context
        val contextMap = parseContextToMap(contextJson)

        // 2. 确定模型配置
        val modelConfig = resolveModelConfig(node)
            ?: error("PromptGraphEngine: 无法找到节点 ${node.id} 使用的模型配置（model_role=${node.modelRole}）")

        logger.debug("PromptGraphEngine: 节点 ${node.id} 使用模型 ${modelConfig.model}（appModelId=${modelConfig.appModelId}）")

        // 3. 确定出边 schema（取第一个有 schema_id 的出边）
        val outEdge = edges.filter { it.sourceNodeId == node.id }.firstOrNull { it.schemaId != null }
        val schemaEntry = outEdge?.schemaId?.let { sid -> schemaRegistry.find { it.id == sid } }

        // 4. 渲染提示词模板（%变量% → context 值）
        val renderedSystem = node.systemPrompt?.let { renderTemplate(it, contextMap) } ?: ""
        val renderedUser   = node.userPromptTpl?.let { renderTemplate(it, contextMap) } ?: ""

        // 5. 构建注入上下文片段（context_include 指定的节点输出）
        val contextSlice = buildContextSlice(node, contextMap)

        // 6. 构建完整 user prompt（追加上下文片段）
        val fullUserPrompt = if (contextSlice.isNotBlank())
            "$renderedUser\n\n---\n相关上下文：\n$contextSlice"
        else
            renderedUser

        logger.debug("PromptGraphEngine: 节点 ${node.id} 提示词渲染完成 systemLen=${renderedSystem.length} userLen=${fullUserPrompt.length} contextSliceLen=${contextSlice.length} schema=${schemaEntry?.id}")

        // 7. 构建 LLM 请求体
        val requestBody = buildLlmRequestBody(
            modelConfig = modelConfig,
            systemPrompt = renderedSystem,
            userPrompt = fullUserPrompt,
            schemaEntry = schemaEntry,
            node = node,
        )

        // 8. 记录 input_snapshot
        val inputSnapshot = buildInputSnapshot(
            renderedSystem = renderedSystem,
            fullUserPrompt = fullUserPrompt,
            schemaEntry = schemaEntry,
            modelConfig = modelConfig,
        )
        PromptNodeRunService.repo.setInputSnapshot(nodeRunId, inputSnapshot)

        // 9. 调用 LLM
        val output = llmCaller(modelConfig, requestBody, cancelSignal)
            ?: error("PromptGraphEngine: LLM 调用被取消 nodeId=${node.id}")

        logger.debug("PromptGraphEngine: 节点 ${node.id} LLM 调用完成 outputLen=${output.length}")

        // 10. 确保输出是有效 JSON（如果有 schema 约束）
        // MVP 简化校验：只检查输出是否以 { 或 [ 开头（是 JSON），不做 JSON Schema 全量校验
        val outputJson = if (schemaEntry != null) {
            ensureJsonOutput(output, node.id)
        } else {
            // 无 schema 约束：将纯文本包装为 JSON 对象
            AppUtil.GlobalVars.json.encodeToString(mapOf("text" to output))
        }

        return outputJson
    }

    /**
     * 执行 TRANSFORM 节点（Kotlin 处理函数）。
     *
     * Phase B MVP 注意：调用 [TransformHandlerRegistry] 中注册的 Kotlin 函数，
     * 而非 prompt-graph.md §2.3 中描述的 JS 表达式。
     */
    private suspend fun executeTransform(
        node: PromptNodeDef,
        nodeRunId: String,
        contextJson: String,
    ): String {
        val handlerKey = node.transformHandler
            ?: error("PromptGraphEngine: TRANSFORM 节点缺少 transform_handler nodeId=${node.id}")

        val handler = TransformHandlerRegistry.get(handlerKey)
            ?: error("PromptGraphEngine: TRANSFORM 处理函数未注册 key=$handlerKey nodeId=${node.id}")

        PromptNodeRunService.repo.setInputSnapshot(nodeRunId, contextJson)

        logger.debug("PromptGraphEngine: TRANSFORM 节点 ${node.id} 执行 handler=$handlerKey")
        return handler(contextJson)
    }

    /**
     * 拓扑排序（Kahn's algorithm）。
     *
     * 边方向：source → target（source 必须先于 target 执行）。
     *
     * ── 起始节点的确定方式 ──────────────────────────────────────────────────────
     * 引擎没有"显式的开始节点"字段。起始节点是通过入度（in-degree）隐式推断出来的：
     *
     *   入度 = 指向该节点的边的数量。
     *
     * 如果一个节点的入度为 0，意味着没有任何其他节点需要在它之前完成，
     * 因此它可以最先执行——这就是起始节点。
     *
     * 示例（双节点串行图）：
     *   node_a ──→ node_b
     *   node_a 的入度 = 0 → 起始节点，第一个执行
     *   node_b 的入度 = 1 → 等待 node_a 完成
     *
     * 示例（并行分叉图）：
     *   root ──→ branch_a
     *   root ──→ branch_b
     *   root 的入度 = 0 → 唯一起始节点
     *   branch_a、branch_b 的入度各为 1 → root 执行完后依次执行
     *
     * 示例（多个起始节点）：
     *   node_x（无入边）→ node_z
     *   node_y（无入边）→ node_z
     *   node_x、node_y 的入度均为 0 → 两者都是起始节点，可任意顺序执行
     *   node_z 的入度 = 2 → 等待 node_x 和 node_y 都完成后执行
     * ──────────────────────────────────────────────────────────────────────────
     *
     * 算法步骤：
     * 1. 计算所有节点的初始入度（有多少条边指向它）
     * 2. 将入度为 0 的节点（即起始节点）加入队列
     * 3. 每次取出队列头节点，将其加入结果序列，
     *    并将其所有后继节点的入度减 1；若后继入度降为 0，则入队
     * 4. 若最终结果大小 ≠ 节点总数，则说明图中存在环，无法执行
     *
     * 注意：跨越不合法节点 ID 的边（source/target 不在 nodes 列表中）会被忽略。
     */
    private fun topologicalSort(nodes: List<PromptNodeDef>, edges: List<PromptEdgeDef>): List<String> {
        val nodeIds = nodes.map { it.id }.toSet()
        // 入度表：节点 id → 有多少条入边（初始全部为 0）
        val inDegree = nodeIds.associateWithTo(mutableMapOf()) { 0 }
        // 邻接表：节点 id → 它指向的所有后继节点 id 列表
        val adj = nodeIds.associateWithTo(mutableMapOf<String, MutableList<String>>()) { mutableListOf() }

        for (edge in edges) {
            // 跳过引用了不存在节点的边（容错处理，正常情况下不应出现）
            if (edge.sourceNodeId !in nodeIds || edge.targetNodeId !in nodeIds) continue
            adj[edge.sourceNodeId]!!.add(edge.targetNodeId)
            // 每条边将目标节点的入度加 1；遍历完所有边后，入度仍为 0 的节点即为起始节点
            inDegree[edge.targetNodeId] = (inDegree[edge.targetNodeId] ?: 0) + 1
        }

        // 起始节点：入度为 0 的节点，即没有任何前置依赖、可立即执行的节点。
        // 一张图可以有多个起始节点（如并行输入图），也可以只有一个（如串行链）。
        val queue = ArrayDeque<String>()
        inDegree.forEach { (id, deg) -> if (deg == 0) queue.addLast(id) }

        val result = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            result.add(cur)
            // 将 cur 的所有后继节点入度减 1；入度降为 0 时说明其所有前置依赖都已处理完毕
            adj[cur]?.forEach { next ->
                inDegree[next] = (inDegree[next] ?: 0) - 1
                if (inDegree[next] == 0) queue.addLast(next)
            }
        }

        if (result.size != nodeIds.size) {
            error("PromptGraphEngine: Graph 含环，无法执行 defId 中节点数=${nodeIds.size} 排序后=${result.size}")
        }

        return result
    }

    /** 解析 context_json 为 Map<String, String>（value 为 JSON 字符串形式）。 */
    private fun parseContextToMap(contextJson: String): Map<String, String> {
        return try {
            val element = AppUtil.GlobalVars.json.parseToJsonElement(contextJson)
            if (element is JsonObject) {
                element.entries.associate { (k, v) -> k to v.toString() }
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            logger.warn("PromptGraphEngine: context_json 解析失败，使用空 Map: ${e.message}")
            emptyMap()
        }
    }

    /** 渲染提示词模板：将 `%变量名%` 替换为 context 中对应 key 的值。
     *
     * context 中的值是 JSON 形式的字符串，例如 `"\"GPIO 简介\""` 或 `"{\"concepts\":[]}"`.
     * 对于纯字符串类型的值（被 JSON 双引号包裹），替换时会去掉外层引号并还原转义，
     * 让提示词中看到的是人类可读的文本，而不是 JSON 编码字符串。
     * 对于对象/数组类型的值，保留原始 JSON 形式（下游节点处理结构化数据时有用）。
     */
    private fun renderTemplate(template: String, context: Map<String, String>): String {
        var result = template
        for ((key, value) in context) {
            // 判断该值是否为 JSON 字符串（以 " 开头和结尾）
            // 是则去掉引号并还原 \" → " 和 \n → 换行，使提示词更自然可读
            val displayValue = if (value.startsWith("\"") && value.endsWith("\"")) {
                value.drop(1).dropLast(1).replace("\\\"", "\"").replace("\\n", "\n")
            } else {
                // 对象/数组类型直接嵌入（保留 JSON 结构）
                value
            }
            result = result.replace("%$key%", displayValue)
        }
        return result
    }

    /** 构建注入上下文片段（按 context_include 白名单过滤，截断至 context_max_chars）。
     *
     * 与 renderTemplate 的区别：
     * - renderTemplate 通过 `%变量%` 语法把值内联替换进提示词（适合短文本输入）
     * - buildContextSlice 在提示词末尾附加完整的节点输出（适合长文档、结构化数据引用）
     *
     * 当 context_include 为空时，不注入额外片段。
     * 这样设计是为了避免重复注入：若 user_prompt_tpl 中已有 %node_a%，
     * 则不应再在 context_slice 中重复输出 node_a 的内容。
     */
    private fun buildContextSlice(node: PromptNodeDef, context: Map<String, String>): String {
        val include = node.contextInclude.ifEmpty {
            emptyList() // 空列表 = 依赖 renderTemplate 已通过 %变量% 注入所有需要的数据
        }

        if (include.isEmpty()) return ""

        val sb = StringBuilder()
        for (key in include) {
            val value = context[key] ?: continue
            // 以 [nodeId] 为标题标注来源，便于 LLM 区分各段上下文
            sb.append("[$key]\n$value\n\n")
            if (sb.length >= node.contextMaxChars) break
        }

        // 硬截断至 contextMaxChars，防止过长上下文撑爆 LLM token 限制
        return sb.take(node.contextMaxChars).toString()
    }

    /**
     * 解析模型配置。
     *
     * 优先级（高 → 低）：
     * 1. `node.modelIdOverride`（app_model_id）：节点直接指定，跳过角色系统
     * 2. `node.modelRole` → AppConfig.llmDefaultRoles → 查找对应 appModelId
     * 3. 兜底：模型列表第一个（防止用户配置不完整时引擎直接崩溃）
     *
     * 返回 null 表示找不到任何可用模型，调用方需中止执行。
     */
    private suspend fun resolveModelConfig(node: PromptNodeDef): LlmModelConfig? {
        val config = AppConfigService.repo.getConfig()
        val models = config.llmModelsJson.loadJsonModel<List<LlmModelConfig>>().getOrElse { emptyList() }

        // 优先级 1：节点直接指定 app_model_id
        if (!node.modelIdOverride.isNullOrBlank()) {
            return models.find { it.appModelId == node.modelIdOverride }
        }

        // 优先级 2：通过 model_role 查 LlmDefaultRoles 映射表
        val defaultRoles = config.llmDefaultRolesJson.loadJsonModel<LlmDefaultRoles>().getOrElse { LlmDefaultRoles() }
        val appModelId = when (node.modelRole) {
            "chat"     -> defaultRoles.chatModelId
            "vision"   -> defaultRoles.visionModelId
            "coding"   -> defaultRoles.codingModelId
            "dev_test" -> defaultRoles.devTestModelId
            else       -> defaultRoles.chatModelId  // 未知角色降级为 chat
        }

        // 优先级 3：兜底返回第一个模型（appModelId 为空或找不到匹配时）
        if (appModelId.isBlank()) return models.firstOrNull()
        return models.find { it.appModelId == appModelId } ?: models.firstOrNull()
    }

    /** 构建 LLM 请求体 JSON 字符串（OpenAI 兼容格式）。
     *
     * stream 固定为 false：引擎需要完整输出后才做 JSON 校验，
     * LlmSseClient.streamChat 在非流式时会自动降级为普通 HTTP 请求。
     * 若有 schemaEntry，注入 response_format 约束 LLM 输出符合 JSON Schema。
     */
    private fun buildLlmRequestBody(
        modelConfig: LlmModelConfig,
        systemPrompt: String,
        userPrompt: String,
        schemaEntry: SchemaEntry?,
        node: PromptNodeDef,
    ): String {
        val temperature = node.temperatureOverride ?: modelConfig.temperature
        val maxTokens = node.maxTokensOverride ?: modelConfig.maxOutputTokens

        val messages = buildString {
            append("[")
            if (systemPrompt.isNotBlank()) {
                append("{\"role\":\"system\",\"content\":${jsonStringify(systemPrompt)}}")
                append(",")
            }
            append("{\"role\":\"user\",\"content\":${jsonStringify(userPrompt)}}")
            append("]")
        }

        val responseFormat = if (schemaEntry != null) {
            // 注入 response_format（JSON Schema 约束）
            ""","response_format":{"type":"json_schema","json_schema":{"name":${jsonStringify(schemaEntry.id)},"strict":true,"schema":${schemaEntry.schema}}}"""
        } else {
            ""
        }

        return """{"model":${jsonStringify(modelConfig.model)},"messages":$messages,"stream":false,"temperature":$temperature,"max_tokens":$maxTokens$responseFormat}"""
    }

    /** 构建 input_snapshot JSON 字符串（调试/复现用）。 */
    private fun buildInputSnapshot(
        renderedSystem: String,
        fullUserPrompt: String,
        schemaEntry: SchemaEntry?,
        modelConfig: LlmModelConfig,
    ): String {
        val schemaIdPart = if (schemaEntry != null) ",\"schema_id\":${jsonStringify(schemaEntry.id)}" else ""
        return """{
            "rendered_system_prompt":${jsonStringify(renderedSystem)},
            "rendered_user_prompt":${jsonStringify(fullUserPrompt.take(2000))},
            "model":${jsonStringify(modelConfig.model)}$schemaIdPart
        }""".trimIndent()
    }

    /** 确保字符串是有效 JSON，否则包装为 {"raw_output": "..."} 。
     *
     * 部分 LLM 在输出 JSON 前后会附加自然语言说明，例如：
     *   "以下是提取结果：\n{\"concepts\":[...]}\n希望这有所帮助。"
     * 此方法找到最外层 `{...}` 或 `[...]` 的位置并提取，
     * 若仍无法解析则包装为 raw_output 保留原始输出，不丢弃数据。
     */
    private fun ensureJsonOutput(output: String, nodeId: String): String {
        val trimmed = output.trim()
        // 尝试找到 JSON 对象/数组的边界（有些 LLM 在 JSON 前后会输出额外文本）
        val jsonStart = trimmed.indexOf('{').takeIf { it >= 0 }
            ?: trimmed.indexOf('[').takeIf { it >= 0 }
        val jsonEnd = trimmed.lastIndexOf('}').takeIf { it >= 0 }
            ?: trimmed.lastIndexOf(']').takeIf { it >= 0 }

        return if (jsonStart != null && jsonEnd != null && jsonStart <= jsonEnd) {
            val extracted = trimmed.substring(jsonStart, jsonEnd + 1)
            // 简单验证：能否被解析
            runCatching { AppUtil.GlobalVars.json.parseToJsonElement(extracted) }
                .map { extracted }
                .getOrElse {
                    logger.warn("PromptGraphEngine: 节点 $nodeId 输出 JSON 无效，包装为 raw_output")
                    """{"raw_output":${jsonStringify(trimmed)}}"""
                }
        } else {
            logger.warn("PromptGraphEngine: 节点 $nodeId 输出不含 JSON 结构，包装为 raw_output")
            """{"raw_output":${jsonStringify(trimmed)}}"""
        }
    }

    /** JSON 字符串转义并添加双引号。 */
    private fun jsonStringify(s: String): String =
        AppUtil.GlobalVars.json.encodeToString(s)

    /** 构建初始 context JSON（从 Map<String, String> 构建，值直接作为 JSON 字符串注入）。 */
    private fun buildInitialContextJson(context: Map<String, String>): String {
        if (context.isEmpty()) return "{}"
        val sb = StringBuilder("{")
        context.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(",")
            sb.append("\"${k.replace("\"", "\\\"")}\":${jsonStringify(v)}")
        }
        sb.append("}")
        return sb.toString()
    }
}
