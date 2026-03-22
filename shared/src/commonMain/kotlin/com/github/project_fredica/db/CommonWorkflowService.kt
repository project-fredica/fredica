package com.github.project_fredica.db

import java.util.UUID

/**
 * 任务 ID 的类型包装，防止与普通字符串混用。
 *
 * 在 [CommonWorkflowService.TaskDef.id] 和 [CommonWorkflowService.TaskDef.dependsOnIds] 中使用，
 * 由类型系统保证 `dependsOnIds` 里只能放真正由本次工作流定义的任务 ID。
 */
@JvmInline
value class TaskId(val value: String) {
    companion object {
        fun random(): TaskId = TaskId(UUID.randomUUID().toString())
    }
}

/**
 * 通用工作流创建服务，封装 WorkflowRun + Task 链的底层 DB 写入。
 *
 * 各业务服务（[MaterialWorkflowService] 等）通过此服务创建实际的工作流实例，
 * 无需直接操作 [WorkflowRunStatusService] / [TaskStatusService]。
 */
object CommonWorkflowService {

    /**
     * 单个任务定义。
     *
     * @param id           任务 ID，类型为 [TaskId]（由调用方预先生成，以便跨任务引用）
     * @param type         任务类型字符串，如 "DOWNLOAD_BILIBILI_VIDEO"
     * @param materialId   关联素材 ID
     * @param payload      任务参数 JSON 字符串
     * @param dependsOnIds 前置任务 ID 列表（[TaskId]），非空时写入 `dependsOn` JSON 数组
     * @param maxRetries   最大重试次数
     */
    data class TaskDef(
        val id: TaskId = TaskId.random(),
        val type: String,
        val materialId: String,
        val payload: String,
        val dependsOnIds: List<TaskId> = emptyList(),
        val maxRetries: Int = 3,
    )

    /**
     * 创建一个 WorkflowRun 及其所有子任务。
     *
     * @return 创建的 workflowRunId
     */
    suspend fun createWorkflow(
        template: String,
        materialId: String,
        tasks: List<TaskDef>,
    ): String {
        require(tasks.isNotEmpty()) { "tasks must not be empty" }
        val nowSec        = System.currentTimeMillis() / 1000L
        val workflowRunId = UUID.randomUUID().toString()

        WorkflowRunStatusService.create(
            WorkflowRun(
                id         = workflowRunId,
                materialId = materialId,
                template   = template,
                status     = "pending",
                totalTasks = tasks.size,
                doneTasks  = 0,
                createdAt  = nowSec,
            )
        )
        TaskStatusService.createAll(
            tasks.map { td ->
                Task(
                    id            = td.id.value,
                    type          = td.type,
                    workflowRunId = workflowRunId,
                    materialId    = td.materialId,
                    payload       = td.payload,
                    dependsOn     = if (td.dependsOnIds.isEmpty()) "[]"
                                    else td.dependsOnIds.joinToString(
                                        prefix = "[\"", separator = "\",\"", postfix = "\"]"
                                    ) { it.value },
                    maxRetries    = td.maxRetries,
                    createdAt     = nowSec,
                )
            }
        )
        return workflowRunId
    }
}
