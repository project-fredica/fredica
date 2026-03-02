package com.github.project_fredica.worker.executors

import com.github.project_fredica.db.Task
import com.github.project_fredica.worker.ExecuteResult
import com.github.project_fredica.worker.TaskExecutor

/**
 * AI 分析任务的占位执行器（Phase 1 尚未实现，返回 stub 结果让 pipeline 可以正常完成）。
 *
 * Phase 3 中将接入本地 Ollama 或 OpenAI Compatible API，
 * endpoint/model 从 AppConfig 读取，此处不需要任何平台依赖，位于 commonMain。
 */
object AiAnalyzeExecutor : TaskExecutor {
    override val taskType = "AI_ANALYZE"

    override suspend fun execute(task: Task): ExecuteResult {
        return ExecuteResult(result = """{"analysis":"[AI_ANALYZE not implemented]"}""")
    }
}
