package com.github.project_fredica.api.routes

// =============================================================================
// NetworkTestRoute —— 触发网速和延迟测试任务
// =============================================================================
//
// 职责：
//   封装创建 NETWORK_TEST 流水线的逻辑，前端无需手动拼接 PipelineCreateRoute 请求体。
//   接收可选的 urls 列表和 timeout_ms，创建单任务流水线，返回 pipeline_id 和 task_id。
//
// 调用方在拿到 pipeline_id 后，通过轮询 WorkerTaskListRoute?pipeline_id=xxx
// 等待任务完成，然后从 task.result 解析测速结果。
// =============================================================================

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.PipelineInstance
import com.github.project_fredica.db.PipelineService
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.UUID

/**
 * POST /api/v1/NetworkTestRoute
 *
 * 请求体（全部可选，省略时使用默认值）：
 * ```json
 * {
 *   "urls": ["https://www.baidu.com", ...],
 *   "timeout_ms": 5000
 * }
 * ```
 *
 * 响应：
 * ```json
 * {"pipeline_id": "uuid", "task_id": "uuid"}
 * ```
 */
object NetworkTestRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "触发网速和延迟测试（创建 NETWORK_TEST 流水线）"

    /** 与 NetworkTestExecutor.Payload 结构相同，用于从请求体反序列化参数。 */
    @Serializable
    data class Param(
        val urls: List<String> = listOf(
            "https://www.baidu.com",
            "https://www.google.com",
            "https://github.com",
            "https://api.openai.com",
        ),
        @SerialName("timeout_ms") val timeoutMs: Int = 5_000,
    )

    override suspend fun handler(param: String): ValidJsonString {
        // 解析请求体，失败时使用默认参数（允许 POST 空 body "{}"）
        val p = param.loadJsonModel<Param>().getOrElse { Param() }
        val nowSec = System.currentTimeMillis() / 1000L
        val pipelineId = UUID.randomUUID().toString()
        val taskId = UUID.randomUUID().toString()

        // 将参数序列化为 task payload，NetworkTestExecutor 会原样解析
        val taskPayload = AppUtil.GlobalVars.json.encodeToString(p)

        val task = Task(
            id = taskId,
            type = "NETWORK_TEST",
            pipelineId = pipelineId,
            materialId = "system",          // 系统任务不关联素材
            status = "pending",
            priority = 5,                 // 中等优先级，不抢占媒体处理任务
            payload = taskPayload,
            createdAt = nowSec,
        )

        val pipeline = PipelineInstance(
            id = pipelineId,
            materialId = "",
            template = "NETWORK_TEST",
            status = "pending",
            totalTasks = 1,
            doneTasks = 0,
            createdAt = nowSec,
        )

        PipelineService.repo.create(pipeline)
        TaskService.repo.createAll(listOf(task))

        return buildValidJson { kv("pipeline_id", pipelineId); kv("task_id", taskId) }
    }
}
