package com.github.project_fredica.db

// =============================================================================
// WorkflowRun —— 工作流运行实例
// =============================================================================
//
// 一次 WorkflowRun 代表对某个素材执行一次处理流程（下载、转码、转录等）。
// 它是一组 Task 的容器，提供整体进度视图（doneTasks / totalTasks）和汇总状态。
//
// 当前阶段（Phase 1.5 前）：WorkflowRun 是轻量过渡模型，与原 PipelineInstance 等价。
// Phase 1.5 起：引入 WorkflowDefinition + WorkflowNodeRun，WorkflowRun 将演化为
//               完整的有向无环图（DAG）运行实例，支持条件分支、中间节点停止等特性。
// 详见 docs/dev/plans/workflow-design.md。
//
// 状态流转：
//   pending → running（有任何任务开始执行时，由 recalculate 推进）
//          → completed（所有任务均 completed）
//          → failed（任意任务达到 max_retries）
//          → cancelled（用户主动取消）
//
// 与 Task 的关系：
//   - Task.workflowRunId → WorkflowRun.id（一对多）
//   - WorkflowRun 的状态由 WorkflowRunDb.recalculate() 根据子任务状态汇总计算
//   - WorkflowRun 不直接存储 Task 列表，需通过 TaskRepo.listByWorkflowRun() 查询
// =============================================================================

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// =============================================================================
// WorkflowRunListResult —— listHistoryByMaterial 分页查询结果
// =============================================================================

@Serializable
data class WorkflowRunListResult(
    val items: List<WorkflowRun>,
    val total: Int,
)

/**
 * 工作流运行实例，记录一次处理流程的整体状态和进度。
 *
 * 注意：[totalTasks] 和 [doneTasks] 由 [WorkflowRunRepo.recalculate] 维护，
 * 不应由调用方手动修改。
 */
@Serializable
data class WorkflowRun(
    /** 运行实例唯一 ID（UUID）。 */
    val id: String,

    /** 关联的素材 ID，与 material_video.id 对应。系统任务传入空字符串。 */
    @SerialName("material_id") val materialId: String,

    /**
     * 工作流模板标识，如 "manual_download_bilibili_video"。
     * Phase 1.5 起将替换为 workflowDefId，指向 WorkflowDefinition 记录。
     */
    val template: String,

    /** 当前状态：pending / running / completed / failed / cancelled */
    val status: String = "pending",

    /** 子任务总数（由 recalculate 从 task 表统计后写入）。 */
    @SerialName("total_tasks") val totalTasks: Int = 0,

    /** 已完成的子任务数（completed 状态的任务数量）。 */
    @SerialName("done_tasks") val doneTasks: Int = 0,

    /** 创建时间（Unix 秒）。 */
    @SerialName("created_at") val createdAt: Long,

    /** 进入 completed 状态的时间（Unix 秒），未完成时为 null。 */
    @SerialName("completed_at") val completedAt: Long? = null,
)

// =============================================================================
// WorkflowRunRepo —— 工作流运行实例数据访问接口
// =============================================================================

interface WorkflowRunRepo {
    /** 创建一条运行实例记录。 */
    suspend fun create(run: WorkflowRun)

    /** 按 id 查询运行实例，不存在时返回 null。 */
    suspend fun getById(id: String): WorkflowRun?

    /**
     * 重新统计子任务状态，更新 workflow_run 的 done_tasks、total_tasks 和 status。
     * 由 WorkerEngine 在每次任务状态变更后触发。
     */
    suspend fun recalculate(workflowRunId: String)

    /**
     * 查询指定素材下所有**活跃**（非终态）WorkflowRun，按创建时间降序，不分页。
     * 供 [com.github.project_fredica.db.MaterialWorkflowService.queryActive] 使用。
     */
    suspend fun listActiveByMaterial(materialId: String): List<WorkflowRun>

    /**
     * 查询指定素材下所有**终态**（completed / failed / cancelled）WorkflowRun，
     * 按创建时间降序，支持分页。
     * 供 [com.github.project_fredica.db.MaterialWorkflowService.queryHistory] 使用。
     */
    suspend fun listHistoryByMaterial(
        materialId: String,
        page: Int = 1,
        pageSize: Int = 20,
    ): WorkflowRunListResult

    /**
     * 对账：对所有非终态 WorkflowRun 重新从 Task 实际状态推导，修正可能落后的汇总状态。
     *
     * 为什么需要此方法：
     *   WorkerEngine 的 recalculate() 在每次任务状态变更后触发，但若该调用因异常
     *   而失败（如 DB 写入错误、协程被取消），WorkflowRun 的汇总状态就会落后于
     *   Task 的实际状态。此方法作为补偿手段，在启动恢复后一次性修正所有遗漏。
     *
     * 对账规则（依次判断）：
     *   1. 若 Task 全部被删除（total_tasks = 0）→ 标记 failed（孤立工作流，无任务可推进）
     *   2. 否则调用 recalculate() 让 Task 实际状态驱动 WorkflowRun 状态
     *      - 有任意 failed Task → failed
     *      - 全部 completed    → completed
     *      - 有 running/pending → running
     *
     * 与 recalculate() 的区别：
     *   - recalculate(id)：针对单个 WF，由 WorkerEngine 在任务完成后实时触发
     *   - reconcileNonTerminal()：针对全部非终态 WF，在启动恢复后批量执行
     *
     * @return 状态被修改的 WorkflowRun 数量
     */
    suspend fun reconcileNonTerminal(): Int
}

// =============================================================================
// WorkflowRunService —— 全局单例，持有 WorkflowRunRepo 实现
// =============================================================================

object WorkflowRunService {
    private var _repo: WorkflowRunRepo? = null

    val repo: WorkflowRunRepo
        get() = _repo ?: error("WorkflowRunService 未初始化，请先调用 WorkflowRunService.initialize()")

    fun initialize(repo: WorkflowRunRepo) {
        _repo = repo
    }
}
