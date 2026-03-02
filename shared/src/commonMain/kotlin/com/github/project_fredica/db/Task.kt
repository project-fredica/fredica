package com.github.project_fredica.db

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// =============================================================================
// Task —— 单个异步工作任务
// =============================================================================
//
// 生命周期：
//   pending → claimed → running → completed
//                              ↘ failed（retry_count < max_retries → 回到 pending）
//   pending/running → cancelled（用户取消流水线）
//
// depends_on 是 JSON 数组，存放前置任务 ID，如 `["task-a","task-b"]`。
// claimNext() 只会认领"所有前置任务都已 completed"的任务，从而实现 DAG 调度。
// =============================================================================

@Serializable
data class Task(
    /** 任务唯一 ID（UUID） */
    val id: String,

    /** 任务类型，对应一个 TaskExecutor 实现，如 DOWNLOAD_VIDEO、EXTRACT_AUDIO 等 */
    val type: String,

    /** 所属流水线 ID，与 pipeline_instance.id 对应 */
    @SerialName("pipeline_id") val pipelineId: String,

    /** 所属素材 ID，与 material.id 对应 */
    @SerialName("material_id") val materialId: String,

    /** 当前状态：pending / claimed / running / completed / failed / cancelled */
    val status: String = "pending",

    /** 调度优先级，数字越大越优先；同优先级按 created_at 升序（先进先出） */
    val priority: Int = 0,

    /** 前置任务 ID 列表（JSON 数组字符串），全部 completed 才允许认领本任务 */
    @SerialName("depends_on") val dependsOn: String = "[]",

    /** 缓存策略（暂未实现）：NONE = 每次都执行 */
    @SerialName("cache_policy") val cachePolicy: String = "NONE",

    /** 执行参数（JSON 字符串），由各 Executor 自定义格式 */
    val payload: String = "{}",

    /** 执行结果（JSON 字符串），成功时由 Executor 写入 */
    val result: String? = null,

    /** 调用方确认收到 result 的时间戳（秒），预留字段 */
    @SerialName("result_acked_at") val resultAckedAt: Long? = null,

    /** 最后一次失败的错误信息（human-readable） */
    val error: String? = null,

    /** 错误类型标签，如 TIMEOUT / IO_ERROR，便于按类型统计或报警 */
    @SerialName("error_type") val errorType: String? = null,

    /** 排除的节点列表（JSON 数组），多节点时跳过这些节点 */
    @SerialName("excluded_nodes") val excludedNodes: String = "[]",

    /** 幂等键；相同 key 的任务只会插入一条（ON CONFLICT DO NOTHING） */
    @SerialName("idempotency_key") val idempotencyKey: String? = null,

    /** 已重试次数（首次执行不计入） */
    @SerialName("retry_count") val retryCount: Int = 0,

    /** 最大重试次数；超过后永久 failed */
    @SerialName("max_retries") val maxRetries: Int = 3,

    /** 创建来源，单节点模式固定为 "local" */
    @SerialName("created_by") val createdBy: String = "local",

    /** 认领本任务的 worker ID */
    @SerialName("claimed_by") val claimedBy: String? = null,

    /** 首次认领者（重新认领时记录原始 worker） */
    @SerialName("original_claimed_by") val originalClaimedBy: String? = null,

    /** 预留：文件节点亲和性（多节点时使用） */
    @SerialName("file_node_id") val fileNodeId: String? = null,

    /** 预留：节点亲和性标签 */
    @SerialName("node_affinity") val nodeAffinity: String? = null,

    /** 创建时间（Unix 秒） */
    @SerialName("created_at") val createdAt: Long,

    /** 被认领时间（Unix 秒） */
    @SerialName("claimed_at") val claimedAt: Long? = null,

    /** 开始执行时间（Unix 秒） */
    @SerialName("started_at") val startedAt: Long? = null,

    /** 完成（成功/失败/取消）时间（Unix 秒） */
    @SerialName("completed_at") val completedAt: Long? = null,

    /** Worker 最后一次心跳时间（Unix 秒），用于检测僵尸任务（预留） */
    @SerialName("heartbeat_at") val heartbeatAt: Long? = null,

    /** 任务被判定为 stale 的时间（Unix 秒），预留 */
    @SerialName("stale_at") val staleAt: Long? = null,

    /** 最近一次被重新认领的时间（Unix 秒），预留 */
    @SerialName("reclaimed_at") val reclaimedAt: Long? = null,
)

// =============================================================================
// TaskRepo —— 任务数据访问接口
// =============================================================================
//
// 注意：recalculate（重新计算流水线进度）不在 TaskRepo 里，
//       因为它需要写 pipeline_instance 表，属于 PipelineRepo 的职责。
// =============================================================================

interface TaskRepo {
    /**
     * 原子认领下一个可执行任务：
     * - status = 'pending'
     * - 所有 depends_on 里的任务都已 'completed'
     * - 按 priority DESC、created_at ASC 排序取第一条
     *
     * 返回 null 表示当前没有可认领的任务。
     */
    suspend fun claimNext(workerId: String): Task?

    /**
     * 更新任务状态，同时记录 result / error / error_type。
     * - 状态变为 running 时自动记录 started_at
     * - 状态变为 completed/failed/cancelled 时自动记录 completed_at
     */
    suspend fun updateStatus(
        id: String,
        status: String,
        result: String? = null,
        error: String? = null,
        errorType: String? = null,
    )

    /**
     * 将 retry_count +1（任务失败可重试时调用，在 reset 到 pending 之前）。
     */
    suspend fun incrementRetry(id: String)

    /** 按 pipeline_id 查询所有任务，按 priority DESC、created_at ASC 排序。 */
    suspend fun listByPipeline(pipelineId: String): List<Task>

    /**
     * 查询所有任务，支持可选过滤：
     * @param pipelineId 为 null 时不过滤 pipeline
     * @param status     为 null 时不过滤 status
     */
    suspend fun listAll(pipelineId: String? = null, status: String? = null): List<Task>

    /** 插入单个任务（内部调用 createAll）。 */
    suspend fun create(task: Task): Task

    /** 批量插入任务；已存在（id 冲突）的任务静默忽略。 */
    suspend fun createAll(tasks: List<Task>)
}

// =============================================================================
// TaskService —— 全局单例，持有 TaskRepo 实现
// =============================================================================
//
// 使用方式：
//   1. 在 FredicaApi.jvm.kt 初始化时调用 TaskService.initialize(taskDb)
//   2. 之后通过 TaskService.repo 访问数据库操作
// =============================================================================

object TaskService {
    private var _repo: TaskRepo? = null

    val repo: TaskRepo
        get() = _repo ?: error("TaskService 未初始化，请先调用 TaskService.initialize()")

    fun initialize(repo: TaskRepo) {
        _repo = repo
    }
}
