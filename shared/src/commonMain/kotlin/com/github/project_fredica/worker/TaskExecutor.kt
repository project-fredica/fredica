package com.github.project_fredica.worker

// =============================================================================
// TaskExecutor —— 任务执行器接口
// =============================================================================
//
// 设计思路：
//   每种任务类型（DOWNLOAD_VIDEO / EXTRACT_AUDIO / ...）对应一个 TaskExecutor 实现。
//   WorkerEngine 在启动时接收 executor 列表，按 taskType 建立注册表（registry）。
//   认领到任务后，引擎根据 task.type 查找对应 executor，调用 execute()。
//
//   这样做的好处：
//   - 新增任务类型只需实现接口并注册，不需要修改引擎核心逻辑
//   - 测试时可以用 FakeExecutor 替换真实实现，无需 ffmpeg/Python 等外部依赖
//   - 纯 Kotlin 接口，可在 commonMain 中使用（平台无关）
//   - JVM 特有的 executor（依赖 ProcessBuilder 等）在 jvmMain 中实现，
//     通过 FredicaApi.jvm.kt 启动时显式传入，不影响其他平台
// =============================================================================

import com.github.project_fredica.db.Task
import kotlinx.serialization.Serializable

/**
 * 任务执行器接口。每个实现类负责处理一种 [taskType] 的任务。
 *
 * 实现约定：
 *   - execute() 是挂起函数，可在 IO 线程上执行耗时操作（ProcessBuilder、HTTP 请求等）
 *   - 执行成功：返回 ExecuteResult(result = "...JSON...")
 *   - 执行失败：返回 ExecuteResult(error = "...", errorType = "...")，**不要抛出异常**
 *     （WorkerEngine 也会 catch 异常，但主动返回失败比抛异常更明确）
 *   - 执行结果 result 字段内容由 WorkerEngine 写入 task.result 列，
 *     下游任务可通过 TaskService.repo.listByWorkflowRun() 读取前置任务的 result 获取输出路径等信息
 */
interface TaskExecutor {
    /** 与 task.type 字段对应的类型标识符，如 "DOWNLOAD_VIDEO"。 */
    val taskType: String

    /**
     * 执行任务并返回结果。
     *
     * @param task  当前被认领的任务，包含 payload（执行参数）和 dependsOn（前置任务 ID）
     * @return      ExecuteResult，成功时 error=null，失败时 error 非空
     */
    suspend fun execute(task: Task): ExecuteResult

    /**
     * 检查任务是否可跳过（前置结果已存在）。
     * 仅当 payload 中 check_skip=true 时由 WorkerEngine 调用。
     * 默认返回 false，子类按需覆写。
     */
    fun canSkip(task: Task): Boolean = false
}

/**
 * 任务执行结果。
 *
 * 判断成功/失败只需检查 [isSuccess]（即 error 是否为 null），
 * 无需区分各种异常类型——类型信息通过 [errorType] 标签传递给监控系统。
 *
 * @param result    成功时写入 task.result 的 JSON 字符串（下游任务可读取）
 * @param error     失败时的人类可读错误信息（写入 task.error）
 * @param errorType 失败类型标签，便于按类型统计或触发报警（写入 task.error_type）
 *                  建议值：PAYLOAD_ERROR / FFMPEG_ERROR / DOWNLOAD_ERROR /
 *                         TRANSCRIBE_ERROR / DEPENDENCY_ERROR / EXCEPTION 等
 */
@Serializable
data class ExecuteResult(
    val result: String = "{}",
    val error: String? = null,
    val errorType: String? = null,
) {
    /** true = 成功（error 为 null），false = 失败（error 有内容）。 */
    val isSuccess get() = error == null
}
