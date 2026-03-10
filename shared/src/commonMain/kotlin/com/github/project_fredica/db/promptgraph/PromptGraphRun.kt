package com.github.project_fredica.db.promptgraph

// =============================================================================
// PromptGraphRun —— 提示词图"运行实例"
// =============================================================================
//
// 管理一张表：prompt_graph_run
//
// 每次调用 PromptGraphEngine.run() 创建一个 PromptGraphRun，
// 记录运行时快照（graph_def_ver、schema_version）与累积上下文（context_json）。
// =============================================================================

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 提示词图运行实例（DB 行模型）。
 *
 * 创建时快照 [graphDefVer] 和 [schemaVersion]，与 [PromptGraphDef] 后续版本升级解耦。
 * 历史 Run 永远基于创建时的 def 版本执行，不受后续版本升级影响。
 */
@Serializable
data class PromptGraphRun(
    /** UUID。 */
    val id: String,
    /** 关联的 PromptGraphDef.id。 */
    @SerialName("prompt_graph_def_id") val promptGraphDefId: String,
    /** 创建时的 PromptGraphDef.version 快照（整数）。 */
    @SerialName("graph_def_ver") val graphDefVer: Int,
    /** 创建时的 PromptGraphDef.schema_version 快照。 */
    @SerialName("schema_version") val schemaVersion: String,
    /** 所属 WorkflowRun.id（若由 WorkflowEngine 触发）；null = 独立运行。 */
    @SerialName("workflow_run_id") val workflowRunId: String? = null,
    /** 所属 WorkflowNodeRun.id（若由 WorkflowEngine 触发）；null = 独立运行。 */
    @SerialName("workflow_node_run_id") val workflowNodeRunId: String? = null,
    /** 关联素材 ID（独立运行时使用，可选）。 */
    @SerialName("material_id") val materialId: String? = null,
    /** 运行状态：'pending' | 'running' | 'completed' | 'failed' | 'paused'。 */
    val status: String = "pending",
    /**
     * 各节点 effectiveOutput() 的累积 JSON 对象（key = node_def_id）。
     *
     * 初始值来自 [PromptGraphEngine.run] 的 initialContext 参数（通常包含输入数据，
     * 如 chunk_text、video_context 等）。每个节点完成后，
     * 引擎将节点的有效输出追加到此对象中。
     */
    @SerialName("context_json") val contextJson: String = "{}",
    /** 记录创建时间，Unix 秒。 */
    @SerialName("created_at") val createdAt: Long,
    /** 运行完成时间，Unix 秒；null = 尚未完成。 */
    @SerialName("completed_at") val completedAt: Long? = null,
)

// =============================================================================
// PromptGraphRunRepo
// =============================================================================

interface PromptGraphRunRepo {
    suspend fun create(run: PromptGraphRun)
    suspend fun getById(id: String): PromptGraphRun?
    suspend fun listByDef(promptGraphDefId: String, limit: Int = 20, offset: Int = 0): List<PromptGraphRun>
    suspend fun updateStatus(id: String, status: String, completedAt: Long? = null)
    /** 追加节点输出到 context_json（合并，不覆盖已有 key）。 */
    suspend fun mergeContext(id: String, nodeKey: String, valueJson: String)
    /** 完整覆写 context_json（仅初始化时使用）。 */
    suspend fun setContext(id: String, contextJson: String)
}

// =============================================================================
// PromptGraphRunService
// =============================================================================

object PromptGraphRunService {
    private var _repo: PromptGraphRunRepo? = null
    val repo: PromptGraphRunRepo
        get() = _repo ?: error("PromptGraphRunService 未初始化，请先调用 initialize()")
    fun initialize(repo: PromptGraphRunRepo) { _repo = repo }
}
