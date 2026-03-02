package com.github.project_fredica.worker.executors

// =============================================================================
// MergeTranscriptionExecutor —— 转录分片合并执行器
// =============================================================================
//
// 功能：
//   从 dependsOn 中列出的所有 TRANSCRIBE_CHUNK 任务的 result 里读取 segments，
//   按 start 时间排序后拼接成完整的转录文本。
//
// 输入（来自前置 TRANSCRIBE_CHUNK 任务的 result JSON）：
//   {"segments": [{"start": 0.0, "end": 2.5, "text": "你好"}, ...], "text": "...", "language": "zh"}
//
// 输出（写入本任务 result 字段）：
//   {"segments": [...按时间排序的全部分片...], "text": "完整转录文本"}
//
// 纯 Kotlin 实现，无平台依赖，位于 commonMain。
// =============================================================================

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskService
import com.github.project_fredica.worker.ExecuteResult
import com.github.project_fredica.worker.TaskExecutor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 合并多个 TRANSCRIBE_CHUNK 任务的转录结果，生成完整的转录数据。
 *
 * Result JSON:
 * ```json
 * {"segments": [...], "text": "full transcript"}
 * ```
 */
object MergeTranscriptionExecutor : TaskExecutor {
    override val taskType = "MERGE_TRANSCRIPTION"
    private val logger = createLogger()

    @Serializable
    private data class Segment(val start: Double, val end: Double, val text: String)

    override suspend fun execute(task: Task): ExecuteResult {
        // 1. 解析前置任务 ID 列表，找出所有 TRANSCRIBE_CHUNK 类型的前置任务
        val depIds = parseIds(task.dependsOn)
        val allTasks = TaskService.repo.listByPipeline(task.pipelineId)
        val depTasks = allTasks.filter { it.id in depIds && it.type == "TRANSCRIBE_CHUNK" }

        if (depTasks.isEmpty()) {
            logger.warn("MergeTranscriptionExecutor: 任务 ${task.id} 没有找到 TRANSCRIBE_CHUNK 前置任务")
        }

        // 2. 从每个 TRANSCRIBE_CHUNK 任务的 result 中提取 segments
        val segments = mutableListOf<Segment>()
        for (dep in depTasks) {
            val result = dep.result ?: continue
            try {
                val segsArray: JsonArray = Json.parseToJsonElement(result)
                    .jsonObject["segments"]?.jsonArray ?: continue
                for (seg in segsArray) {
                    val obj = seg.jsonObject
                    segments.add(
                        Segment(
                            start = obj["start"]!!.jsonPrimitive.double,
                            end   = obj["end"]!!.jsonPrimitive.double,
                            text  = obj["text"]!!.jsonPrimitive.content,
                        )
                    )
                }
            } catch (e: Throwable) {
                logger.warn("MergeTranscriptionExecutor: 解析前置任务 ${dep.id} 的 segments 失败: ${e.message}")
            }
        }

        // 3. 按开始时间排序，拼接完整文本
        segments.sortBy { it.start }
        val fullText = segments.joinToString(" ") { it.text.trim() }

        // 4. 构造输出 JSON（手动拼接避免引入额外序列化依赖）
        val segsJson = segments.joinToString(",", "[", "]") { seg ->
            """{"start":${seg.start},"end":${seg.end},"text":"${seg.text.replace("\"", "\\\"")}"}"""
        }
        val resultJson = """{"segments":$segsJson,"text":"${fullText.replace("\"", "\\\"")}"}"""

        logger.info("MergeTranscriptionExecutor: 合并了 ${depTasks.size} 个分片，共 ${segments.size} 条 segment")
        return ExecuteResult(result = resultJson)
    }

    /** 将 JSON 数组字符串（如 `["id1","id2"]`）解析为 ID 集合。 */
    private fun parseIds(json: String): Set<String> = try {
        json.trim('[', ']').split(',')
            .map { it.trim('"', ' ') }
            .filter { it.isNotBlank() }
            .toSet()
    } catch (_: Throwable) { emptySet() }
}
