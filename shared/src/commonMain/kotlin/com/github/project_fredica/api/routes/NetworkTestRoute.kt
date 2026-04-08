package com.github.project_fredica.api.routes

// =============================================================================
// NetworkTestRoute —— 触发网速和延迟测试任务
// =============================================================================
//
// 职责：
//   封装创建 NETWORK_TEST 流水线的逻辑，前端无需手动拼接 PipelineCreateRoute 请求体。
//   接收可选的 urls 列表和 timeout_ms，创建单任务工作流运行实例，返回 workflow_run_id 和 task_id。
//
// 调用方在拿到 workflow_run_id 后，通过轮询 WorkerTaskListRoute?workflow_run_id=xxx
// 等待任务完成，然后从 task.result 解析测速结果。
// =============================================================================

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.WorkflowRun
import com.github.project_fredica.db.WorkflowRunStatusService
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskStatusService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.UUID

/** 默认测试目标，NetworkTestRoute 和 NetworkTestConfigRoute 共用同一份。 */
val NETWORK_TEST_DEFAULT_URLS = listOf(
    "https://www.baidu.com",
    "https://www.google.com",
    "https://github.com",
    "https://api.openai.com",
    "https://api.deepseek.com",
)

object NetworkTestRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "触发网速和延迟测试（创建 NETWORK_TEST 流水线）"

    /** 与 NetworkTestExecutor.Payload 结构相同，用于从请求体反序列化参数。 */
    @Serializable
    data class Param(
        val urls: List<String> = NETWORK_TEST_DEFAULT_URLS,
        @SerialName("timeout_ms") val timeoutMs: Int = 5_000,
    )

    override suspend fun handler(param: String): ValidJsonString {
        // 解析请求体，失败时使用默认参数（允许 POST 空 body "{}"）
        val p = param.loadJsonModel<Param>().getOrElse { Param() }
        val nowSec       = System.currentTimeMillis() / 1000L
        val workflowRunId = UUID.randomUUID().toString()
        val taskId = UUID.randomUUID().toString()

        // 将参数序列化为 task payload，NetworkTestExecutor 会原样解析
        val taskPayload = AppUtil.GlobalVars.json.encodeToString(p)

        val task = Task(
            id = taskId,
            type = "NETWORK_TEST",
            workflowRunId = workflowRunId,
            materialId = "system",          // 系统任务不关联素材
            status = "pending",
            priority = 5,                 // 中等优先级，不抢占媒体处理任务
            payload = taskPayload,
            createdAt = nowSec,
        )

        val run = WorkflowRun(
            id = workflowRunId,
            materialId = "",
            template = "NETWORK_TEST",
            status = "pending",
            totalTasks = 1,
            doneTasks = 0,
            createdAt = nowSec,
        )

        WorkflowRunStatusService.create(run)
        TaskStatusService.createAll(listOf(task))

        return buildValidJson { kv("workflow_run_id", workflowRunId); kv("task_id", taskId) }
    }
}
