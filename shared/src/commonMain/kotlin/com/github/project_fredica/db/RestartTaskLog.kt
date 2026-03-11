package com.github.project_fredica.db

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// =============================================================================
// RestartTaskLog —— 应用重启中断任务日志
// =============================================================================
//
// 每次应用强杀重启时，resetStaleTasks() 会将所有非终态任务取消。
// 本模型记录被取消的任务快照，并跟踪用户的最终处置方式。
//
// disposition 枚举值：
//   pending_review — 默认，用户尚未处置
//   dismissed      — 用户手动忽略
//   recreated      — 用户已重新提交相同参数的新任务
//   superseded     — 被下一次重启自动覆盖（系统写入，用户不可选）
// =============================================================================

@Serializable
data class RestartTaskLog(
    val id: String,
    @SerialName("session_id")        val sessionId: String,
    @SerialName("task_id")           val taskId: String,
    @SerialName("task_type")         val taskType: String,
    @SerialName("workflow_run_id")   val workflowRunId: String,
    @SerialName("material_id")       val materialId: String,
    @SerialName("status_at_restart") val statusAtRestart: String,
    val payload:     String  = "{}",
    val progress:    Int     = 0,
    val disposition: String  = "pending_review",
    @SerialName("new_workflow_run_id") val newWorkflowRunId: String? = null,
    @SerialName("created_at")  val createdAt:  Long,
    @SerialName("resolved_at") val resolvedAt: Long? = null,
)

@Serializable
data class RestartTaskLogListResult(
    val items: List<RestartTaskLog>,
    @SerialName("pending_review_count") val pendingReviewCount: Int,
)

interface RestartTaskLogRepo {
    suspend fun initialize()

    /** 批量写入一次重启事件的中断任务快照。
     *  写入前先将所有已存在的 pending_review 条目批量更新为 superseded，防止重启积累。 */
    suspend fun recordRestartSession(sessionId: String, tasks: List<Task>, nowSec: Long)

    /** 查询日志（按 disposition / materialId 过滤；null = 全部），按 created_at DESC 排序。 */
    suspend fun listAll(disposition: String? = null, materialId: String? = null): List<RestartTaskLog>

    /** 统计 pending_review 数量（用于 UI 角标）。 */
    suspend fun countPendingReview(): Int

    /** 更新处置方式；ids 和 sessionId 二选一（ids 优先）；
     *  提供 newWorkflowRunId 则 disposition 为 recreated，否则为传入的 disposition 值。 */
    suspend fun updateDisposition(
        ids: List<String>? = null,
        sessionId: String? = null,
        disposition: String,
        newWorkflowRunId: String? = null,
    )
}

object RestartTaskLogService {
    private var _repo: RestartTaskLogRepo? = null

    val repo: RestartTaskLogRepo
        get() = _repo ?: error("RestartTaskLogService 未初始化，请先调用 RestartTaskLogService.initialize()")

    fun initialize(repo: RestartTaskLogRepo) {
        _repo = repo
    }
}
