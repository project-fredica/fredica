package com.github.project_fredica.api.routes

// =============================================================================
// PromptSandboxResult — GraalJS 沙箱执行结果数据类（commonMain）
// =============================================================================

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 沙箱执行过程中产生的单条日志记录。 */
@Serializable
data class PromptSandboxLog(
    /** 日志级别："log" | "warn" | "error" */
    val level: String,
    /** 日志内容（多参数已 join 为单字符串）。 */
    val args: String,
    /** Unix 毫秒时间戳。 */
    val ts: Long,
)

/**
 * GraalJS 沙箱单次执行的完整结果。
 *
 * - 成功时 [promptText] 非 null，[error] 为 null。
 * - 失败时 [error] 非 null，[errorType] 指明失败原因。
 * - [logs] 包含执行过程中 console.log/warn/error 产生的记录。
 */
@Serializable
data class PromptSandboxResult(
    @SerialName("prompt_text") val promptText: String?,
    val error: String?,
    @SerialName("error_type") val errorType: String?,
    val logs: List<PromptSandboxLog>,
)
