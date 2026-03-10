package com.github.project_fredica.db.promptgraph

// =============================================================================
// PromptNodeRun —— 单节点执行记录
// =============================================================================
//
// 管理一张表：prompt_node_run
//
// 每个节点对应一条 PromptNodeRun 记录，存储：
//   - 执行时的完整输入快照（input_snapshot_json）
//   - LLM 原始输出（output_json，永不修改）
//   - 人工覆盖数据（override_json）
//   - 有效输出 = override_json ?: output_json
// =============================================================================

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 单节点执行记录（DB 行模型）。
 *
 * 有效输出读取规则（单一真相来源）：
 * ```kotlin
 * val effectiveOutput: String? = overrideJson ?: outputJson
 * ```
 * 所有下游节点、context 构建、前端展示，统一使用此规则。
 */
@Serializable
data class PromptNodeRun(
    /** UUID。 */
    val id: String,
    /** 所属运行实例（prompt_graph_run.id）。 */
    @SerialName("prompt_graph_run_id") val promptGraphRunId: String,
    /** 对应 PromptNodeDef.id。 */
    @SerialName("node_def_id") val nodeDefId: String,
    /**
     * 节点状态：
     * - pending   — 等待执行（前置节点未完成）
     * - running   — 执行中
     * - completed — 执行完成，output_json 已写入
     * - failed    — 执行失败（含重试耗尽）
     * - overridden — 用户已手动覆盖，override_json 有值
     * - stale     — 上游已被覆盖，此节点结果已过时（等待重跑）
     * - skipped   — 被 CONDITION 节点跳过（Phase B 不使用）
     */
    val status: String = "pending",
    /**
     * 执行时的完整输入快照（JSON 字符串）。
     * 存储渲染后的 system_prompt、user_prompt、context_slice 等，
     * 用于复现、调试、diff 对比。null = 尚未执行。
     */
    @SerialName("input_snapshot_json") val inputSnapshotJson: String? = null,
    /**
     * LLM 原始输出（JSON 字符串）。
     * schema 校验通过后写入，**永不修改**（保留历史快照用于审计和对比）。
     */
    @SerialName("output_json") val outputJson: String? = null,
    /**
     * 人工覆盖数据（JSON 字符串）。
     * null = 未覆盖；非 null = 以此为准（覆盖 output_json）。
     */
    @SerialName("override_json") val overrideJson: String? = null,
    /** 覆盖操作者（"user" | 系统迁移标识）。 */
    @SerialName("override_by") val overrideBy: String? = null,
    /** 覆盖时间，Unix 秒。 */
    @SerialName("override_at") val overrideAt: Long? = null,
    /** 用户填写的修正说明（可选）。 */
    @SerialName("override_note") val overrideNote: String? = null,
    /** 覆盖后的下游策略：'KEEP' | 'INVALIDATE'（默认）| 'RERUN' | 'RERUN_ALL'。 */
    @SerialName("downstream_policy") val downstreamPolicy: String = "INVALIDATE",
    /** 实际输入 token 数；null = 未统计或非 LLM 节点。 */
    @SerialName("tokens_input") val tokensInput: Int? = null,
    /** 实际输出 token 数；null = 未统计或非 LLM 节点。 */
    @SerialName("tokens_output") val tokensOutput: Int? = null,
    /** 实际成本（USD）；null = 未统计。 */
    @SerialName("cost_usd") val costUsd: Double? = null,
    /** 记录创建时间，Unix 秒。 */
    @SerialName("created_at") val createdAt: Long,
    /** 节点完成时间，Unix 秒；null = 尚未完成。 */
    @SerialName("completed_at") val completedAt: Long? = null,
) {
    /** 有效输出（单一真相来源）：override_json 存在时优先返回，否则返回 output_json。 */
    val effectiveOutput: String? get() = overrideJson ?: outputJson
}

// =============================================================================
// PromptNodeRunRepo
// =============================================================================

interface PromptNodeRunRepo {
    /**
     * 创建节点运行记录。
     * UNIQUE(prompt_graph_run_id, node_def_id) 冲突时忽略（幂等，防止并发重复激活）。
     */
    suspend fun create(nodeRun: PromptNodeRun)
    suspend fun getById(id: String): PromptNodeRun?
    suspend fun getByRunAndNode(promptGraphRunId: String, nodeDefId: String): PromptNodeRun?
    suspend fun listByRun(promptGraphRunId: String): List<PromptNodeRun>
    suspend fun updateStatus(id: String, status: String, completedAt: Long? = null)
    /** 写入执行输入快照（执行前调用）。 */
    suspend fun setInputSnapshot(id: String, inputSnapshotJson: String)
    /** 写入 LLM 原始输出（永不修改，执行后调用）。 */
    suspend fun setOutput(id: String, outputJson: String, tokensInput: Int? = null, tokensOutput: Int? = null)
    /** 提交人工覆盖（调用方先校验 schema，再调用此方法）。 */
    suspend fun setOverride(id: String, overrideJson: String, overrideBy: String, overrideAt: Long, overrideNote: String?)
}

// =============================================================================
// PromptNodeRunService
// =============================================================================

object PromptNodeRunService {
    private var _repo: PromptNodeRunRepo? = null
    val repo: PromptNodeRunRepo
        get() = _repo ?: error("PromptNodeRunService 未初始化，请先调用 initialize()")
    fun initialize(repo: PromptNodeRunRepo) { _repo = repo }
}
