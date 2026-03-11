package com.github.project_fredica.worker.executors

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.json
import com.github.project_fredica.api.routes.PromptGraphEngineService
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskService
import com.github.project_fredica.db.promptgraph.PromptGraphRunService
import com.github.project_fredica.db.promptgraph.PromptNodeRunService
import com.github.project_fredica.db.weben.WebenConcept
import com.github.project_fredica.db.weben.WebenConceptAlias
import com.github.project_fredica.db.weben.WebenConceptService
import com.github.project_fredica.db.weben.WebenConceptSource
import com.github.project_fredica.db.weben.WebenFlashcard
import com.github.project_fredica.db.weben.WebenFlashcardService
import com.github.project_fredica.db.weben.WebenRelation
import com.github.project_fredica.db.weben.WebenRelationService
import com.github.project_fredica.db.weben.WebenSegment
import com.github.project_fredica.db.weben.WebenSegmentConcept
import com.github.project_fredica.db.weben.WebenSegmentService
import com.github.project_fredica.db.weben.WebenSourceService
import com.github.project_fredica.worker.ExecuteResult
import com.github.project_fredica.worker.TaskPauseResumeChannels
import com.github.project_fredica.worker.WebSocketTaskExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 从文本块中提取概念/关系/闪卡，写入 Weben 知识图谱数据库。
 *
 * ## Payload 格式
 * ```json
 * {
 *   "source_id":           "uuid",
 *   "text_path":           "/path/source_text.txt",
 *   "video_title":         "视频标题",
 *   "video_description":   "视频简介（可选）",
 *   "material_id":         "uuid（可选）"
 * }
 * ```
 *
 * ## 执行流程
 * 1. 读 source_text.txt，若空则标记 completed 直接返回
 * 2. 按 1500 字符切块（尊重句号/换行边界）
 * 3. 对每块：建 WebenSegment → 调 PromptGraphEngine → 写概念/关系/闪卡
 * 4. 更新 WebenSource.analysisStatus = "completed"
 * 5. 写 concept_extract.done
 *
 * ## 跳过机制（canSkip）
 * 检测 `{webenSourceDir}/concept_extract.done` 是否存在。
 */
object WebenConceptExtractExecutor : WebSocketTaskExecutor() {
    override val taskType = "WEBEN_CONCEPT_EXTRACT"
    private val logger = createLogger()

    private const val CHUNK_MAX_CHARS = 1500
    private const val PROMPT_GRAPH_DEF_ID = "system:weben_video_concept_extract"

    @Serializable
    private data class Payload(
        @SerialName("source_id")           val sourceId: String,
        @SerialName("text_path")           val textPath: String,
        @SerialName("video_title")         val videoTitle: String = "",
        @SerialName("video_description")   val videoDescription: String = "",
        @SerialName("material_id")         val materialId: String? = null,
    )

    override suspend fun canSkip(task: Task): Boolean {
        val payload = runCatching { Json.decodeFromString<Payload>(task.payload) }.getOrNull() ?: return false
        val done = AppUtil.Paths.webenSourceDir(payload.sourceId).resolve("concept_extract.done").exists()
        logger.debug("WebenConceptExtractExecutor.canSkip: sourceId=${payload.sourceId} done=$done")
        return done
    }

    /**
     * 任务永久失败或取消时，将 WebenSource.analysisStatus 重置为 "failed"。
     * 覆写此回调而非在 executeWithSignals 各路径散落调用，保持业务状态更新的统一收口。
     */
    override suspend fun onTaskFailed(task: Task, result: ExecuteResult) {
        val sourceId = runCatching {
            Json.decodeFromString<Payload>(task.payload).sourceId
        }.getOrNull() ?: return
        runCatching { WebenSourceService.repo.updateAnalysisStatus(sourceId, "failed") }
            .onFailure { logger.warn("WebenConceptExtractExecutor.onTaskFailed: 更新状态失败 sourceId=$sourceId: ${it.message}") }
        logger.info("WebenConceptExtractExecutor.onTaskFailed: sourceId=$sourceId → failed (errorType=${result.errorType})")
    }

    override suspend fun executeWithSignals(
        task: Task,
        cancelSignal: CompletableDeferred<Unit>,
        pauseResumeChannels: TaskPauseResumeChannels,
    ): ExecuteResult = withContext(Dispatchers.IO) {
        val payload = try {
            Json.decodeFromString<Payload>(task.payload)
        } catch (e: Throwable) {
            logger.error("WebenConceptExtractExecutor: payload 解析失败，taskId=${task.id}: ${e.message}")
            return@withContext ExecuteResult(error = "Invalid payload: ${e.message}", errorType = "PAYLOAD_ERROR")
        }

        val textFile = File(payload.textPath)
        val text = if (textFile.exists()) textFile.readText().trim() else ""

        if (text.isEmpty()) {
            logger.info("WebenConceptExtractExecutor: 文本为空，直接标记完成 sourceId=${payload.sourceId} [taskId=${task.id}]")
            markDone(payload.sourceId)
            return@withContext ExecuteResult(result = buildValidJson { kv("skipped", true) }.str)
        }

        val chunks = chunkText(text, CHUNK_MAX_CHARS)
        logger.info("WebenConceptExtractExecutor: 文本切块 chunks=${chunks.size} sourceId=${payload.sourceId} [taskId=${task.id}]")

        WebenSourceService.repo.updateAnalysisStatus(payload.sourceId, "analyzing")

        for ((i, chunk) in chunks.withIndex()) {
            if (cancelSignal.isCompleted) {
                logger.info("WebenConceptExtractExecutor: 取消信号，停止 chunk 处理 [taskId=${task.id}]")
                return@withContext ExecuteResult(error = "用户已取消", errorType = "CANCELLED")
            }

            val nowSec = System.currentTimeMillis() / 1000L

            // 建 WebenSegment
            val segment = WebenSegment(
                id        = java.util.UUID.randomUUID().toString(),
                sourceId  = payload.sourceId,
                seq       = i,
                startSec  = 0.0,
                endSec    = 0.0,
                createdAt = nowSec,
            )
            WebenSegmentService.repo.upsert(segment)

            // 调 PromptGraphEngine
            val initialCtx = mapOf(
                "chunk_text"        to chunk.text,
                "video_title"       to payload.videoTitle,
                "video_description" to payload.videoDescription,
            )

            logger.debug("WebenConceptExtractExecutor: 运行 PromptGraph chunk=$i sourceId=${payload.sourceId} [taskId=${task.id}]")
            val runId = try {
                PromptGraphEngineService.runner.run(
                    defId          = PROMPT_GRAPH_DEF_ID,
                    initialContext = initialCtx,
                    materialId     = payload.materialId,
                    workflowRunId  = null,
                    cancelSignal   = cancelSignal,
                )
            } catch (e: Throwable) {
                if (cancelSignal.isCompleted) {
                    return@withContext ExecuteResult(error = "用户已取消", errorType = "CANCELLED")
                }
                logger.error("WebenConceptExtractExecutor: PromptGraph 失败 chunk=$i [taskId=${task.id}]: ${e.message}")
                continue  // 单块失败不中止整体，继续下一块
            }

            // 读取节点输出
            val nodeRuns = PromptNodeRunService.repo.listByRun(runId)
            val conceptOut   = nodeRuns.firstOrNull { it.nodeDefId == "concept_extract"  }?.effectiveOutput
            val relationOut  = nodeRuns.firstOrNull { it.nodeDefId == "relation_extract" }?.effectiveOutput
            val flashcardOut = nodeRuns.firstOrNull { it.nodeDefId == "flashcard_gen"    }?.effectiveOutput

            // 写概念/关系/闪卡
            val conceptIds = upsertConcepts(conceptOut, payload.sourceId, segment.id, nowSec)
            upsertRelations(relationOut, conceptIds, nowSec)
            upsertFlashcards(flashcardOut, conceptIds, payload.sourceId, nowSec)

            val progress = (i + 1) * 100 / chunks.size
            TaskService.repo.updateProgress(task.id, progress)
            WebenSourceService.syncProgressFromGraph(task.workflowRunId)
            logger.info("WebenConceptExtractExecutor: chunk $i 完成 progress=$progress% [taskId=${task.id}]")
        }

        // 所有 chunk 处理完成，同步最终进度（此时 task 仍 running，progress=100，
        // 已完成 task1 贡献 100 → 整体平均值正确到达 100%）
        WebenSourceService.syncProgressFromGraph(task.workflowRunId)
        WebenSourceService.repo.updateAnalysisStatus(payload.sourceId, "completed")
        markDone(payload.sourceId)

        ExecuteResult(result = buildValidJson {
            kv("chunks_processed", chunks.size)
            kv("source_id", payload.sourceId)
        }.str)
    }

    // -------------------------------------------------------------------------
    // 文本切块
    // -------------------------------------------------------------------------

    private data class TextChunk(val text: String, val startChar: Int, val endChar: Int)

    private fun chunkText(text: String, maxChars: Int): List<TextChunk> {
        val chunks = mutableListOf<TextChunk>()
        var pos = 0
        while (pos < text.length) {
            val end = (pos + maxChars).coerceAtMost(text.length)
            // 尽量在句号或换行处截断
            val boundary = if (end < text.length) {
                findBoundary(text, pos, end)
            } else {
                end
            }
            chunks.add(TextChunk(text.substring(pos, boundary).trim(), pos, boundary))
            pos = boundary
            // 跳过空白
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
        return chunks.filter { it.text.isNotEmpty() }
    }

    private fun findBoundary(text: String, start: Int, preferredEnd: Int): Int {
        // 从 preferredEnd 向前找句号/换行
        for (i in preferredEnd downTo (start + CHUNK_MAX_CHARS / 2)) {
            val c = text[i]
            if (c == '。' || c == '\n' || c == '.' || c == '！' || c == '？' || c == '!' || c == '?') {
                return i + 1
            }
        }
        return preferredEnd
    }

    // -------------------------------------------------------------------------
    // 概念写入（去重）
    // -------------------------------------------------------------------------

    private suspend fun upsertConcepts(
        conceptJson: String?,
        sourceId: String,
        segmentId: String,
        nowSec: Long,
    ): Map<String, String> {
        if (conceptJson.isNullOrBlank()) {
            logger.debug("WebenConceptExtractExecutor.upsertConcepts: conceptJson 为空，跳过")
            return emptyMap()
        }
        val idMap = mutableMapOf<String, String>()
        try {
            val root = AppUtil.GlobalVars.json.parseToJsonElement(conceptJson).jsonObject
            val concepts = root["concepts"]?.jsonArray ?: run {
                logger.debug("WebenConceptExtractExecutor.upsertConcepts: JSON 中无 concepts 数组")
                return emptyMap()
            }
            logger.debug("WebenConceptExtractExecutor.upsertConcepts: 待写入 ${concepts.size} 个概念 sourceId=$sourceId segmentId=$segmentId")
            for (el in concepts) {
                val obj  = el.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content?.trim() ?: continue
                val type = obj["type"]?.jsonPrimitive?.content ?: "术语"
                val brief = obj["brief_definition"]?.jsonPrimitive?.content
                val aliases = obj["aliases"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
                val timestampHints = obj["timestamp_hints"]?.jsonArray
                    ?.mapNotNull { runCatching { it.jsonPrimitive.content.toDouble() }.getOrNull() }
                    ?: emptyList()

                val canonicalKey = name.trim().lowercase()

                // 去重：先查 DB，已存在则更新 last_seen_at，不存在则新建
                val existing = WebenConceptService.repo.getByCanonicalName(canonicalKey)
                val conceptId = if (existing != null) {
                    logger.debug("WebenConceptExtractExecutor.upsertConcepts: 概念已存在，更新 lastSeenAt name=$name id=${existing.id}")
                    WebenConceptService.repo.update(existing.copy(lastSeenAt = nowSec, updatedAt = nowSec))
                    existing.id
                } else {
                    val newId = java.util.UUID.randomUUID().toString()
                    logger.debug("WebenConceptExtractExecutor.upsertConcepts: 新建概念 name=$name type=$type id=$newId")
                    WebenConceptService.repo.create(
                        WebenConcept(
                            id              = newId,
                            canonicalName   = canonicalKey,
                            conceptType     = type,
                            briefDefinition = brief,
                            confidence      = 0.8,
                            firstSeenAt     = nowSec,
                            lastSeenAt      = nowSec,
                            createdAt       = nowSec,
                            updatedAt       = nowSec,
                        )
                    )
                    newId
                }

                idMap[name] = conceptId

                // 添加别名（已存在则 DB 层幂等忽略）
                if (aliases.isNotEmpty()) {
                    logger.debug("WebenConceptExtractExecutor.upsertConcepts: 添加别名 conceptId=$conceptId aliases=$aliases")
                }
                for (alias in aliases) {
                    WebenConceptService.repo.addAlias(
                        WebenConceptAlias(conceptId = conceptId, alias = alias, aliasSource = "LLM提取")
                    )
                }

                // 关联来源（含 timestamp_sec，无时间戳则用 null）
                if (timestampHints.isNotEmpty()) {
                    logger.debug("WebenConceptExtractExecutor.upsertConcepts: 关联来源时间戳 conceptId=$conceptId timestamps=$timestampHints")
                    for (ts in timestampHints) {
                        WebenConceptService.repo.addSource(
                            WebenConceptSource(conceptId = conceptId, sourceId = sourceId, timestampSec = ts)
                        )
                    }
                } else {
                    // 无时间戳时关联无时间戳的来源记录
                    WebenConceptService.repo.addSource(
                        WebenConceptSource(conceptId = conceptId, sourceId = sourceId)
                    )
                }

                // 关联 segment（将概念与当前文本切块绑定，用于时间轴展示）
                WebenSegmentService.repo.linkConcept(
                    WebenSegmentConcept(segmentId = segmentId, conceptId = conceptId)
                )
            }
            logger.debug("WebenConceptExtractExecutor.upsertConcepts: 完成 idMap.size=${idMap.size} sourceId=$sourceId")
        } catch (e: Throwable) {
            logger.warn("WebenConceptExtractExecutor.upsertConcepts: 解析失败 ${e.message}")
        }
        return idMap
    }

    // -------------------------------------------------------------------------
    // 关系写入
    // -------------------------------------------------------------------------

    private suspend fun upsertRelations(
        relationJson: String?,
        conceptIds: Map<String, String>,
        nowSec: Long,
    ) {
        if (relationJson.isNullOrBlank()) {
            logger.debug("WebenConceptExtractExecutor.upsertRelations: relationJson 为空，跳过")
            return
        }
        try {
            val root = AppUtil.GlobalVars.json.parseToJsonElement(relationJson).jsonObject
            val relations = root["relations"]?.jsonArray ?: run {
                logger.debug("WebenConceptExtractExecutor.upsertRelations: JSON 中无 relations 数组")
                return
            }
            logger.debug("WebenConceptExtractExecutor.upsertRelations: 待写入 ${relations.size} 条关系")
            var written = 0
            var skipped = 0
            for (el in relations) {
                val obj         = el.jsonObject
                val subjectName = obj["subject"]?.jsonPrimitive?.content ?: continue
                val predicate   = obj["predicate"]?.jsonPrimitive?.content ?: continue
                val objectName  = obj["object"]?.jsonPrimitive?.content ?: continue
                val confidence  = obj["confidence"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.8

                // 优先从当前 chunk 的 idMap 查找，再回落到 DB（处理跨 chunk 引用）
                val subjectId = conceptIds[subjectName]
                    ?: WebenConceptService.repo.getByCanonicalName(subjectName.trim().lowercase())?.id
                val objectId = conceptIds[objectName]
                    ?: WebenConceptService.repo.getByCanonicalName(objectName.trim().lowercase())?.id

                if (subjectId == null || objectId == null) {
                    logger.debug(
                        "WebenConceptExtractExecutor.upsertRelations: 跳过关系（概念未找到）" +
                        " subject=$subjectName(${if (subjectId == null) "未找到" else "ok"})" +
                        " predicate=$predicate object=$objectName(${if (objectId == null) "未找到" else "ok"})"
                    )
                    skipped++
                    continue
                }

                logger.debug(
                    "WebenConceptExtractExecutor.upsertRelations: 写入关系" +
                    " subject=$subjectName predicate=$predicate object=$objectName confidence=$confidence"
                )
                WebenRelationService.repo.upsert(
                    WebenRelation(
                        id         = java.util.UUID.randomUUID().toString(),
                        subjectId  = subjectId,
                        predicate  = predicate,
                        objectId   = objectId,
                        confidence = confidence,
                        isManual   = false,
                        createdAt  = nowSec,
                        updatedAt  = nowSec,
                    )
                )
                written++
            }
            logger.debug("WebenConceptExtractExecutor.upsertRelations: 完成 written=$written skipped=$skipped")
        } catch (e: Throwable) {
            logger.warn("WebenConceptExtractExecutor.upsertRelations: 解析失败 ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // 闪卡写入
    // -------------------------------------------------------------------------

    private suspend fun upsertFlashcards(
        flashcardJson: String?,
        conceptIds: Map<String, String>,
        sourceId: String,
        nowSec: Long,
    ) {
        if (flashcardJson.isNullOrBlank()) {
            logger.debug("WebenConceptExtractExecutor.upsertFlashcards: flashcardJson 为空，跳过")
            return
        }
        try {
            val root = AppUtil.GlobalVars.json.parseToJsonElement(flashcardJson).jsonObject
            val flashcards = root["flashcards"]?.jsonArray ?: run {
                logger.debug("WebenConceptExtractExecutor.upsertFlashcards: JSON 中无 flashcards 数组")
                return
            }
            logger.debug("WebenConceptExtractExecutor.upsertFlashcards: 待写入 ${flashcards.size} 张闪卡 sourceId=$sourceId")
            var written = 0
            var skipped = 0
            for (el in flashcards) {
                val obj         = el.jsonObject
                val conceptName = obj["concept_name"]?.jsonPrimitive?.content ?: continue
                val question    = obj["question"]?.jsonPrimitive?.content ?: continue
                val answer      = obj["answer"]?.jsonPrimitive?.content ?: continue
                val cardType    = obj["card_type"]?.jsonPrimitive?.content ?: "qa"

                // 优先从当前 chunk idMap 查找，再回落 DB（处理跨 chunk 引用）
                val conceptId = conceptIds[conceptName]
                    ?: WebenConceptService.repo.getByCanonicalName(conceptName.trim().lowercase())?.id

                if (conceptId == null) {
                    logger.debug(
                        "WebenConceptExtractExecutor.upsertFlashcards: 跳过闪卡（概念未找到）" +
                        " conceptName=$conceptName"
                    )
                    skipped++
                    continue
                }

                logger.debug(
                    "WebenConceptExtractExecutor.upsertFlashcards: 创建闪卡" +
                    " conceptName=$conceptName cardType=$cardType conceptId=$conceptId"
                )
                WebenFlashcardService.repo.create(
                    WebenFlashcard(
                        id           = java.util.UUID.randomUUID().toString(),
                        conceptId    = conceptId,
                        sourceId     = sourceId,
                        question     = question,
                        answer       = answer,
                        cardType     = cardType,
                        isSystem     = true,
                        nextReviewAt = nowSec,
                        createdAt    = nowSec,
                    )
                )
                written++
            }
            logger.debug("WebenConceptExtractExecutor.upsertFlashcards: 完成 written=$written skipped=$skipped sourceId=$sourceId")
        } catch (e: Throwable) {
            logger.warn("WebenConceptExtractExecutor.upsertFlashcards: 解析失败 ${e.message}")
        }
    }

    private fun markDone(sourceId: String) {
        val doneFile = AppUtil.Paths.webenSourceDir(sourceId).resolve("concept_extract.done")
        doneFile.writeText("ok")
        logger.debug("WebenConceptExtractExecutor.markDone: 已写入完成标记 path=${doneFile.absolutePath}")
    }
}
