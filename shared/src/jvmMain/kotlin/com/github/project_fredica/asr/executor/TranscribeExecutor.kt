package com.github.project_fredica.asr.executor

import com.github.project_fredica.asr.srt.SrtTimestamp
import com.github.project_fredica.asr.model.TranscribeChunkDoneFile
import com.github.project_fredica.asr.model.TranscribeChunkMeta
import com.github.project_fredica.asr.model.TranscribeChunkStartInfo
import com.github.project_fredica.asr.model.TranscribePayload
import com.github.project_fredica.asr.model.TranscribeSegment
import com.github.project_fredica.asr.model.TranscribeTranscriptDoneFile
import com.github.project_fredica.asr.model.TranscribeTranscriptMeta
import com.github.project_fredica.asr.model.TranscriptBestLanguage
import com.github.project_fredica.asr.service.AsrConfigService
import com.github.project_fredica.asr.service.FasterWhisperInstallService
import com.github.project_fredica.apputil.AppProxyService
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.loadJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskService
import com.github.project_fredica.python.PythonUtil
import com.github.project_fredica.worker.ExecuteResult
import com.github.project_fredica.worker.GpuResourceLock
import com.github.project_fredica.worker.TaskPauseResumeChannels
import com.github.project_fredica.worker.WebSocketTaskExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Whisper 语音识别（单个 chunk 音频文件）。
 *
 * ## Payload 格式
 * ```json
 * {
 *   "audio_path":        "/path/to/chunk_0000.m4a",
 *   "language":          "zh",           // 必填，"auto" 表示自动检测
 *   "model_size":        "large-v3",     // 必填
 *   "output_dir":        "/path/to/asr_result/",  // 输出目录
 *   "chunk_index":       0,              // chunk 序号
 *   "total_chunks":      1,              // chunk 总数
 *   "chunk_offset_sec":  0.0,            // 时间偏移（秒），用于多 chunk 绝对时间戳
 *   "allow_download":    false,
 *   "output_path":       null            // deprecated，兼容旧 payload
 * }
 * ```
 *
 * ## 数据生命周期
 * 1. 启动 → 写入 chunk_XXXX.start_info.json（参数快照）
 * 2. 备份旧 .jsonl → chunk_XXXX.{date}.bck.jsonl
 * 3. Python 流式推送 segment → 追加到 chunk_XXXX.jsonl + 内存收集
 * 4. Python done 消息 → 写入 chunk_XXXX.meta.json + chunk_XXXX.srt + chunk_XXXX.done
 * 5. 若 total_chunks 全部完成 → 合并为 transcript.srt + transcript.meta.json + transcript.done
 * 6. MaterialSubtitleListRoute 检测 transcript.done/chunk_*.srt 存在性
 * 7. MaterialAsrSubtitleRoute 读取 transcript.srt 或拼接 chunk_*.srt
 * 8. 取消时 → 写入 partial meta + partial srt，不写 .done
 *
 * ## 跳过机制（canSkip）
 * 检测 chunk_XXXX.done 是否存在，比对 input_hash + model_size + language。
 */
object TranscribeExecutor : WebSocketTaskExecutor() {
    override val taskType = "TRANSCRIBE"
    private val logger = createLogger { "TranscribeExecutor" }

    // ── 工具函数 ─────────────────────────────────────────────────────────────

    private fun chunkPrefix(index: Int) = "chunk_%04d".format(index)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(65536)
            var n: Int
            while (ins.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256String(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(text.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun nowIso(): String = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

    /** ISO 时间戳转文件名安全字符串：将 ':' 替换为 '-' */
    private fun isoToFilenameSafe(iso: String): String = iso.replace(':', '-')

    /**
     * 解析输出目录：优先 outputDir，回退到 outputPath 的父目录。
     * 返回 null 表示无法确定输出目录。
     */
    private fun resolveOutputDir(payload: TranscribePayload): File? = when {
        payload.outputDir.isNotBlank() -> File(payload.outputDir)
        payload.outputPath != null -> File(payload.outputPath).parentFile
        else -> null
    }

    // ── canSkip ──────────────────────────────────────────────────────────────

    override suspend fun canSkip(task: Task): Boolean {
        val payload = runCatching { task.payload.loadJsonModel<TranscribePayload>().getOrThrow() }.getOrNull() ?: return false
        val outDir = resolveOutputDir(payload) ?: return false
        val prefix = chunkPrefix(payload.chunkIndex)
        val doneFile = outDir.resolve("$prefix.done")
        if (!doneFile.exists()) {
            logger.debug("TranscribeExecutor.canSkip: done 文件不存在 [chunkIndex=${payload.chunkIndex}]")
            return false
        }
        val done = doneFile.readText().loadJsonModel<TranscribeChunkDoneFile>().getOrNull()
        if (done == null) {
            logger.debug("TranscribeExecutor.canSkip: done 文件解析失败，重新转录")
            return false
        }
        val audioFile = File(payload.audioPath)
        if (!audioFile.exists()) {
            logger.debug("TranscribeExecutor.canSkip: 音频文件不存在 path=${payload.audioPath}")
            return false
        }
        val currentHash = withContext(Dispatchers.IO) { sha256(audioFile) }
        val hashMatch = done.inputHash == currentHash
        val modelMatch = done.modelSize == payload.modelSize
        // "auto" 表示自动检测，不严格匹配语言（因为每次检测结果可能不同）
        val langMatch = payload.language == "auto" || done.language == payload.language
        logger.debug(
            "TranscribeExecutor.canSkip: hashMatch=$hashMatch modelMatch=$modelMatch langMatch=$langMatch" +
                    " storedHash=${done.inputHash.take(12)}… currentHash=${currentHash.take(12)}…" +
                    " payload.language=${payload.language} done.language=${done.language}"
        )
        return hashMatch && modelMatch && langMatch
    }

    // ── executeWithSignals ───────────────────────────────────────────────────

    override suspend fun executeWithSignals(
        task: Task,
        cancelSignal: CompletableDeferred<Unit>,
        pauseResumeChannels: TaskPauseResumeChannels,
    ): ExecuteResult = withContext(Dispatchers.IO) {
        val payload = try {
            task.payload.loadJsonModel<TranscribePayload>().getOrThrow()
        } catch (e: Throwable) {
            logger.error("TranscribeExecutor: payload 解析失败，taskId=${task.id}: ${e.message}")
            return@withContext ExecuteResult(error = "Invalid payload: ${e.message}", errorType = "PAYLOAD_ERROR")
        }

        val outDir = resolveOutputDir(payload)
        if (outDir == null) {
            logger.error("TranscribeExecutor: 无法确定输出目录 [taskId=${task.id}]")
            return@withContext ExecuteResult(error = "output_dir 和 output_path 均为空", errorType = "PAYLOAD_ERROR")
        }
        outDir.mkdirs()

        val prefix = chunkPrefix(payload.chunkIndex)
        val modelSize = payload.modelSize

        GpuResourceLock.withGpuLock(task.id, task.priority) {
            val cfg = AppConfigService.repo.getConfig()
            val effectiveComputeType =
                cfg.fasterWhisperComputeType.takeIf { it.isNotBlank() && it != "auto" } ?: "float16"
            val proxyUrl = AppProxyService.readProxyUrl()

            // Step 1: 写入 start_info.json
            val startInfo = TranscribeChunkStartInfo(
                startedAt = nowIso(),
                audioPath = payload.audioPath,
                modelSize = modelSize,
                language = payload.language,
                computeType = effectiveComputeType,
            )
            val startInfoFile = outDir.resolve("$prefix.start_info.json")
            startInfoFile.writeText(AppUtil.dumpJsonStr(startInfo).getOrThrow().str)

            // Step 2: 备份旧 .jsonl
            val jsonlFile = outDir.resolve("$prefix.jsonl")
            if (jsonlFile.exists()) {
                backupJsonl(outDir, prefix, jsonlFile)
            }
            // 创建新空 .jsonl
            jsonlFile.writeText("")

            // 权限覆盖：服主配置的 allowDownload 优先于 payload 参数
            val effectiveAllowDownload = AsrConfigService.isDownloadAllowed() && payload.allowDownload

            val paramJson = buildJsonObject {
                put("audio_path", payload.audioPath)
                put("language", payload.language)
                put("model_name", modelSize)
                put("device", "auto")
                put("compute_type", effectiveComputeType)
                put("allow_download", effectiveAllowDownload)
                put("proxy", proxyUrl)
            }.toString()
            logger.info("TranscribeExecutor: 开始转录 audio=${payload.audioPath} [taskId=${task.id}]")
            logger.debug("TranscribeExecutor: params [taskId=${task.id}]: model=$modelSize, language=${payload.language}, computeType=$effectiveComputeType, proxy=${proxyUrl.ifBlank { "(none)" }}, chunkIndex=${payload.chunkIndex}, offsetSec=${payload.chunkOffsetSec}")

            // 确保 faster-whisper 已安装
            TaskService.repo.updateStatusText(task.id, "正在检查 faster-whisper 依赖…")
            val installError = FasterWhisperInstallService.ensureInstalled()
            TaskService.repo.updateStatusText(task.id, null)
            if (installError != null) {
                return@withGpuLock ExecuteResult(
                    error = "faster-whisper 安装失败: $installError",
                    errorType = "INSTALL_ERROR"
                )
            }

            // Step 3: 流式转录，追加 .jsonl + 内存收集
            val segments = CopyOnWriteArrayList<TranscribeSegment>()
            val offset = payload.chunkOffsetSec

            try {
                val result = PythonUtil.Py314Embed.PyUtilServer.websocketTask(
                    pth = "/audio/transcribe-chunk-task",
                    paramJson = paramJson,
                    onProgress = { pct -> TaskService.repo.updateProgress(task.id, pct) },
                    onRawMessage = { line ->
                        runCatching {
                            val obj = line.loadJson().getOrNull()?.let { it as? JsonObject } ?: return@runCatching
                            when (obj["type"]?.jsonPrimitive?.content) {
                                "segment" -> {
                                    val start =
                                        obj["start"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@runCatching
                                    val end = obj["end"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@runCatching
                                    val text = obj["text"]?.jsonPrimitive?.content ?: return@runCatching
                                    val segment = TranscribeSegment(start + offset, end + offset, text)
                                    segments.add(segment)
                                    logger.debug(
                                        "[taskId=${task.id}] segment #${segments.size} ${segment.toPrettyString()}"
                                    )
                                    // 追加到 .jsonl（绝对时间戳）
                                    jsonlFile.appendText(segment.toJsonLine())
                                }

                                "status_text" -> {
                                    val text = obj["text"]?.jsonPrimitive?.content ?: return@runCatching
                                    TaskService.repo.updateStatusText(task.id, text)
                                }

                                "effective_compute_type" -> {
                                    val ct = obj["value"]?.jsonPrimitive?.content ?: return@runCatching
                                    logger.info("TranscribeExecutor: 持久化 effective_compute_type=$ct [taskId=${task.id}]")
                                    val current = AppConfigService.repo.getConfig()
                                    AppConfigService.repo.updateConfig(current.copy(fasterWhisperComputeType = ct))
                                }
                            }
                        }
                    },
                    cancelSignal = cancelSignal,
                    pauseChannel = pauseResumeChannels.pause,
                    resumeChannel = pauseResumeChannels.resume,
                )
                if (result == null) {
                    // 取消：写入 partial meta + partial srt，不写 .done
                    TaskService.repo.updateStatusText(task.id, null)
                    writeChunkMeta(
                        outDir,
                        prefix,
                        payload.language,
                        modelSize,
                        segments.size,
                        partial = true,
                        coreStartSec = payload.coreStartSec,
                        coreEndSec = payload.coreEndSec
                    )
                    writeChunkSrt(outDir, prefix, segments)
                    logger.info("TranscribeExecutor: 已取消，写入 partial 文件 [taskId=${task.id}]")
                    ExecuteResult(error = "用户已取消", errorType = "CANCELLED")
                } else {
                    // Step 4: 转录完成 → 写入 meta + srt + done
                    TaskService.repo.updateStatusText(task.id, null)
                    val language = runCatching {
                        result.loadJson().getOrThrow().let { it as? JsonObject }
                            ?.get("language")?.jsonPrimitive?.content
                    }.getOrNull() ?: payload.language
                    logger.info("TranscribeExecutor: chunk ${payload.chunkIndex} 转录完成，检测语言=$language，请求语言=${payload.language} [taskId=${task.id}]")

                    writeChunkMeta(
                        outDir,
                        prefix,
                        language,
                        modelSize,
                        segments.size,
                        partial = false,
                        coreStartSec = payload.coreStartSec,
                        coreEndSec = payload.coreEndSec
                    )
                    writeChunkSrt(outDir, prefix, segments)
                    writeChunkDone(outDir, prefix, File(payload.audioPath), segments, modelSize, language)

                    logger.info("TranscribeExecutor: 写入 ${segments.size} 段 → $prefix.srt + $prefix.done [taskId=${task.id}]")
                    logger.debug("TranscribeExecutor: 结果详情 [taskId=${task.id}]: segmentCount=${segments.size}, language=$language, outputDir=${outDir.absolutePath}")

                    // Step 5: 检查是否所有 chunk 完成，若是则合并
                    tryMergeChunks(outDir, payload.totalChunks, modelSize, language)

                    ExecuteResult(result = result)
                }
            } catch (e: CancellationException) {
                // CancellationException 必须重新抛出，不能吞掉
                TaskService.repo.updateStatusText(task.id, null)
                writeChunkMeta(
                    outDir, prefix, payload.language, modelSize, segments.size,
                    partial = true, coreStartSec = payload.coreStartSec, coreEndSec = payload.coreEndSec,
                )
                writeChunkSrt(outDir, prefix, segments)
                throw e
            } catch (e: Throwable) {
                TaskService.repo.updateStatusText(task.id, null)
                if (cancelSignal.isCompleted) {
                    writeChunkMeta(
                        outDir,
                        prefix,
                        payload.language,
                        modelSize,
                        segments.size,
                        partial = true,
                        coreStartSec = payload.coreStartSec,
                        coreEndSec = payload.coreEndSec
                    )
                    writeChunkSrt(outDir, prefix, segments)
                    ExecuteResult(error = "用户已取消", errorType = "CANCELLED")
                } else {
                    logger.error("TranscribeExecutor: 失败 [taskId=${task.id}]: ${e.message}")
                    ExecuteResult(error = "Transcribe failed: ${e.message}", errorType = "TRANSCRIBE_ERROR")
                }
            }
        }
    }

    // ── Per-chunk 文件写入 ───────────────────────────────────────────────────

    private fun writeChunkMeta(
        outDir: File, prefix: String, language: String, modelSize: String, segmentCount: Int, partial: Boolean,
        coreStartSec: Double? = null, coreEndSec: Double? = null,
    ) {
        val meta = TranscribeChunkMeta(
            language = language,
            modelSize = modelSize,
            segmentCount = segmentCount,
            completedAt = nowIso(),
            partial = partial,
            coreStartSec = coreStartSec,
            coreEndSec = coreEndSec,
        )
        outDir.resolve("$prefix.meta.json").writeText(AppUtil.dumpJsonStr(meta).getOrThrow().str)
    }

    private fun writeChunkSrt(outDir: File, prefix: String, segments: List<TranscribeSegment>) {
        outDir.resolve("$prefix.srt").writeText(TranscribeSegment.buildSrt(segments))
    }

    private fun writeChunkDone(
        outDir: File,
        prefix: String,
        audioFile: File,
        segments: List<TranscribeSegment>,
        modelSize: String,
        language: String
    ) {
        val inputHash = if (audioFile.exists()) sha256(audioFile) else ""
        val srtContent = TranscribeSegment.buildSrt(segments)
        val outputHash = sha256String(srtContent)
        val done = TranscribeChunkDoneFile(
            inputHash = inputHash,
            outputHash = outputHash,
            modelSize = modelSize,
            language = language,
        )
        outDir.resolve("$prefix.done").writeText(AppUtil.dumpJsonStr(done).getOrThrow().str)
        logger.debug("TranscribeExecutor: writeChunkDone $prefix language=$language modelSize=$modelSize inputHash=${inputHash.take(12)}…")
    }

    /**
     * 备份旧 .jsonl 文件。
     * 读取 start_info.json 的 started_at 字段作为备份文件名的时间戳部分。
     */
    private fun backupJsonl(outDir: File, prefix: String, jsonlFile: File) {
        val startInfoFile = outDir.resolve("$prefix.start_info.json")
        val timestamp = if (startInfoFile.exists()) {
            runCatching {
                val info = startInfoFile.readText().loadJsonModel<TranscribeChunkStartInfo>().getOrThrow()
                isoToFilenameSafe(info.startedAt)
            }.getOrElse { isoToFilenameSafe(nowIso()) }
        } else {
            isoToFilenameSafe(nowIso())
        }
        val backupFile = outDir.resolve("$prefix.$timestamp.bck.jsonl")
        jsonlFile.renameTo(backupFile)
        logger.debug("TranscribeExecutor: 备份旧 .jsonl → ${backupFile.name}")
    }

    // ── 合并逻辑 ─────────────────────────────────────────────────────────────

    /**
     * 检查所有 chunk 是否完成，若是则合并为 transcript.srt + transcript.meta.json + transcript.done。
     *
     * @param lastChunkLanguage 当前最后完成 chunk 的检测语言，作为聚合失败时的兜底。
     */
    private fun tryMergeChunks(outDir: File, totalChunks: Int, modelSize: String, lastChunkLanguage: String) {
        val doneCount = (0 until totalChunks).count { i ->
            outDir.resolve("${chunkPrefix(i)}.done").exists()
        }
        if (doneCount < totalChunks) {
            logger.debug("TranscribeExecutor: 合并条件未满足 ($doneCount/$totalChunks chunks done)")
            return
        }

        logger.info("TranscribeExecutor: 全部 $totalChunks chunks 完成，开始合并")

        // 跨 chunk 语言聚合：读取各 chunk.done 的 language 字段，多数投票
        val chunkDetectedLanguages = (0 until totalChunks).mapNotNull { i ->
            val doneFile = outDir.resolve("${chunkPrefix(i)}.done")
            doneFile.takeIf { it.exists() }?.readText()
                ?.loadJsonModel<TranscribeChunkDoneFile>()?.getOrNull()?.language
        }
        val aggregatedLanguage = if (chunkDetectedLanguages.isEmpty()) {
            lastChunkLanguage
        } else {
            chunkDetectedLanguages.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                ?: lastChunkLanguage
        }
        logger.info(
            "TranscribeExecutor: 跨 chunk 语言聚合: [${chunkDetectedLanguages.joinToString(", ")}] → $aggregatedLanguage"
        )

        // 按 chunk index 顺序读取所有 SRT，按 core region 过滤后拼接
        val allSegments = mutableListOf<TranscribeSegment>()
        var totalSegmentCount = 0
        for (i in 0 until totalChunks) {
            val srtFile = outDir.resolve("${chunkPrefix(i)}.srt")
            if (!srtFile.exists()) continue
            val chunkSegments = TranscribeSegment.parseSrt(srtFile.readText())
            totalSegmentCount += chunkSegments.size

            // 读取 ChunkMeta 获取 core region
            val metaFile = outDir.resolve("${chunkPrefix(i)}.meta.json")
            val meta = metaFile.takeIf { it.exists() }?.readText()
                ?.loadJsonModel<TranscribeChunkMeta>()?.getOrNull()
            val coreStart = meta?.coreStartSec
            val coreEnd = meta?.coreEndSec

            val filtered = if (coreStart != null && coreEnd != null) {
                chunkSegments.filter { seg ->
                    val midpoint = (seg.start + seg.end) / 2.0
                    // 最后一个 chunk 使用闭区间 [core_start, core_end]
                    if (i == totalChunks - 1) {
                        midpoint >= coreStart && midpoint <= coreEnd
                    } else {
                        midpoint >= coreStart && midpoint < coreEnd
                    }
                }
            } else {
                chunkSegments // 无 core region 信息时保留全部（向后兼容）
            }

            if (coreStart != null && coreEnd != null) {
                logger.debug("TranscribeExecutor: chunk $i 合并过滤 ${chunkSegments.size} → ${filtered.size} segments (core=[${coreStart}s, ${coreEnd}s))")
            }
            allSegments.addAll(filtered)
        }

        // 写入合并后的 transcript.srt
        val mergedSrt = TranscribeSegment.buildSrt(allSegments)
        val transcriptSrtFile = outDir.resolve("transcript.srt")
        transcriptSrtFile.writeText(mergedSrt)

        // 写入 transcript.meta.json
        val meta = TranscribeTranscriptMeta(
            modelSize = modelSize,
            language = aggregatedLanguage,
            totalSegments = allSegments.size,
            totalChunks = totalChunks,
            completedAt = nowIso(),
        )
        outDir.resolve("transcript.meta.json").writeText(AppUtil.dumpJsonStr(meta).getOrThrow().str)

        // 写入 transcript.done
        val srtHash = sha256String(mergedSrt)
        val done = TranscribeTranscriptDoneFile(outputHash = srtHash, chunkCount = totalChunks)
        outDir.resolve("transcript.done").writeText(AppUtil.dumpJsonStr(done).getOrThrow().str)

        // 写入 transcript_best_language.json（语言聚合结果）
        if (chunkDetectedLanguages.isNotEmpty()) {
            val countsByLang = chunkDetectedLanguages.groupingBy { it }.eachCount()
            val total = chunkDetectedLanguages.size
            val confidence = (countsByLang[aggregatedLanguage] ?: 0).toDouble() / total
            val chunkLangMap = (0 until totalChunks).mapNotNull { i ->
                val lang = outDir.resolve("${chunkPrefix(i)}.done").takeIf { it.exists() }
                    ?.readText()?.loadJsonModel<TranscribeChunkDoneFile>()?.getOrNull()?.language
                if (lang != null) i.toString() to lang else null
            }.toMap()
            val bestLanguage = TranscriptBestLanguage(
                language = aggregatedLanguage,
                confidence = confidence,
                chunkLanguages = chunkLangMap,
                determinedAt = nowIso(),
            )
            outDir.resolve("transcript_best_language.json")
                .writeText(AppUtil.dumpJsonStr(bestLanguage).getOrThrow().str)
        }

        logger.info("TranscribeExecutor: 合并完成 → transcript.srt (${allSegments.size} segments, raw $totalSegmentCount)")
    }
}
