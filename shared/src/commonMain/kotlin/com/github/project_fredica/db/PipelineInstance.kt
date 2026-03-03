package com.github.project_fredica.db

// =============================================================================
// PipelineInstance —— 流水线实例
// =============================================================================
//
// 一条"流水线"代表对某个素材执行一次完整处理流程（下载→提取音频→转录→分析等）。
// 它是一组 Task 的容器，提供整体进度视图（done_tasks / total_tasks）和汇总状态。
//
// 状态流转：
//   pending → running（有任何任务开始执行时，由 recalculate 推进）
//          → completed（所有任务均 completed）
//          → failed（任意任务达到 max_retries）
//          → cancelled（用户主动取消）
//
// 与 Task 的关系：
//   - Task.pipeline_id → PipelineInstance.id（一对多）
//   - PipelineInstance 的状态由 PipelineDb.recalculate() 根据子任务状态汇总计算
//   - PipelineInstance 不直接存储 Task 列表，需通过 TaskRepo.listByPipeline() 查询
// =============================================================================

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 流水线实例，记录一次处理流程的整体状态和进度。
 *
 * 注意：[totalTasks] 和 [doneTasks] 由 [PipelineRepo.recalculate] 维护，
 * 不应由调用方手动修改。
 */
@Serializable
data class PipelineInstance(
    /** 流水线唯一 ID（UUID），由 PipelineCreateRoute 生成。 */
    val id: String,

    /** 关联的素材 ID，与 material_video.id 对应。 若无，传入空字符串。 */
    @SerialName("material_id") val materialId: String,

    /** 流水线模板名称，如 "FULL_PIPELINE" / "TRANSCRIBE_ONLY"，由调用方自定义。 */
    val template: String,

    /** 当前状态：pending / running / completed / failed / cancelled */
    val status: String = "pending",

    /** 子任务总数（由 recalculate 从 task 表统计后写入）。 */
    @SerialName("total_tasks") val totalTasks: Int = 0,

    /** 已完成的子任务数（completed 状态的任务数量）。 */
    @SerialName("done_tasks") val doneTasks: Int = 0,

    /** 创建时间（Unix 秒）。 */
    @SerialName("created_at") val createdAt: Long,

    /** 流水线进入 completed 状态的时间（Unix 秒），未完成时为 null。 */
    @SerialName("completed_at") val completedAt: Long? = null,
)

// =============================================================================
// TaskEvent —— 任务状态变更的审计事件
// =============================================================================
//
// Phase 1 暂未主动写入事件，接口预留给 Phase 3 的多节点同步使用。
// 多节点场景下，每次状态变更都会广播 TaskEvent 给其他节点，
// 其他节点通过回放事件日志来同步本地 task 表。
// =============================================================================

/**
 * 任务状态变更事件，记录"谁在何时做了什么"，用于审计和多节点同步。
 */
@Serializable
data class TaskEvent(
    /** 事件唯一 ID（UUID）。 */
    val id: String,

    /** 触发本事件的任务 ID。 */
    @SerialName("task_id") val taskId: String,

    /** 触发事件的节点 ID（单节点模式为 "local-node-1"）。 */
    @SerialName("node_id") val nodeId: String,

    /**
     * 事件类型，描述发生了什么操作：
     * - CLAIMED：任务被认领
     * - STARTED：任务开始执行
     * - COMPLETED：任务成功完成
     * - FAILED：任务执行失败
     * - RETRIED：任务被重置为 pending 准备重试
     * - CANCELLED：任务被取消
     */
    @SerialName("event_type") val eventType: String,

    /** 可选的附加说明（如错误信息摘要）。 */
    val message: String? = null,

    /** 事件发生时间（Unix 秒）。 */
    @SerialName("occurred_at") val occurredAt: Long,
)

// =============================================================================
// PipelineRepo —— 流水线数据访问接口
// =============================================================================

// =============================================================================
// PipelineListQuery / PipelinePage —— 分页查询参数与响应
// =============================================================================

/**
 * [PipelineRepo.listPaged] 的查询参数。
 *
 * @param status   按状态过滤（null = 不过滤）
 * @param template 按模板名过滤（null = 不过滤）
 * @param page     页码（1 起始）
 * @param pageSize 每页条数（1‒100，默认 20）
 */
@Serializable
data class PipelineListQuery(
    val status: String? = null,
    val template: String? = null,
    val page: Int = 1,
    @SerialName("page_size") val pageSize: Int = 20,
)

/**
 * [PipelineRepo.listPaged] 的分页响应。
 *
 * @param items      当前页的流水线列表
 * @param total      符合过滤条件的总记录数
 * @param page       实际生效的页码
 * @param pageSize   实际生效的每页条数
 * @param totalPages 总页数（最小为 1）
 */
@Serializable
data class PipelinePage(
    val items: List<PipelineInstance>,
    val total: Int,
    val page: Int,
    @SerialName("page_size") val pageSize: Int,
    @SerialName("total_pages") val totalPages: Int,
)

// =============================================================================
// PipelineRepo —— 流水线数据访问接口
// =============================================================================

interface PipelineRepo {
    /** 创建一条流水线记录。 */
    suspend fun create(pipeline: PipelineInstance)

    /** 按 id 查询流水线，不存在时返回 null。 */
    suspend fun getById(id: String): PipelineInstance?

    /**
     * 分页查询流水线，默认按 created_at 倒序排列。
     * 支持按 [PipelineListQuery.status] 和 [PipelineListQuery.template] 过滤。
     */
    suspend fun listPaged(query: PipelineListQuery = PipelineListQuery()): PipelinePage

    /**
     * 取消流水线：将所有 pending 任务改为 cancelled，流水线本身也标记为 cancelled。
     * 正在执行（running/claimed）的任务不受影响，它们会自然完成。
     *
     * @return 被取消的任务数量
     */
    suspend fun cancel(id: String): Int

    /**
     * 重新统计子任务状态，更新 pipeline 的 done_tasks、total_tasks 和 status。
     * 由 WorkerEngine 在每次任务状态变更后触发。
     */
    suspend fun recalculate(pipelineId: String)

    /** 写入一条审计事件（Phase 3 多节点同步使用）。 */
    suspend fun addEvent(event: TaskEvent)

    /**
     * 检查指定（素材 ID + 模板）是否已有活跃（非终态）的流水线。
     * 用于 PipelineCreateRoute 的幂等创建检查，防止同一素材同时运行两条流水线。
     *
     * 活跃 = status NOT IN ('completed', 'failed', 'cancelled')
     */
    suspend fun hasActivePipeline(materialId: String, template: String): Boolean
}

// =============================================================================
// PipelineService —— 全局单例，持有 PipelineRepo 实现
// =============================================================================
//
// 使用方式：
//   1. 在 FredicaApi.jvm.kt 初始化时调用 PipelineService.initialize(pipelineDb)
//   2. 之后通过 PipelineService.repo 访问数据库操作
//   3. WorkerEngine 在任务完成后调用 PipelineService.repo.recalculate()
// =============================================================================

object PipelineService {
    private var _repo: PipelineRepo? = null

    val repo: PipelineRepo
        get() = _repo ?: error("PipelineService 未初始化，请先调用 PipelineService.initialize()")

    fun initialize(repo: PipelineRepo) {
        _repo = repo
    }
}
