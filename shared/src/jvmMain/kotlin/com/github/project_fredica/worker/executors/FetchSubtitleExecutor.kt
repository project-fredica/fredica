package com.github.project_fredica.worker.executors

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.Task
import com.github.project_fredica.worker.ExecuteResult
import com.github.project_fredica.worker.TaskPauseResumeChannels
import com.github.project_fredica.worker.WebSocketTaskExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


// 请重构 FetchSubtitleExecutor，我已将旧代码注释掉。
//
/**
 * 获取视频字幕文本，优先使用 Bilibili 字幕轨，无字幕时兜底使用 Whisper ASR。
 *
 * ## Payload 格式
 * ```json
 * {
 *   "material_id":      "",              // 素材 ID（ASR 兜底时用于定位视频文件）
 * }
 * ```
 *
 * 通过传入的 material_id，先去检查是否是 bilibili 视频素材。
 *
 * 如果是的话解析 bvid 和分 P index，然后通过 BilibiliSubtitleMetaCacheService 和 BilibiliSubtitleBodyCacheService
 * 去查询字幕。如果此时字幕不存在则去检查本地 fasterWhisper 兼容性，如果失败了则
 */
object FetchSubtitleExecutor : WebSocketTaskExecutor() {
    override val taskType = "FETCH_SUBTITLE"
    private val logger = createLogger()

    @Serializable
    private data class Payload(
        @SerialName("material_id") val materialId: String? = null,
    )

    private fun Task.readPayload(): Payload {
        return payload.loadJsonModel<Payload>().getOrThrow()
    }

    private suspend fun cacheDir(materialId: String) =
        AppUtil.Paths.materialMediaDir(materialId).resolve("subtitles").also { it.mkdirs() }


    override suspend fun canSkip(task: Task): Boolean {
        val payload = task.readPayload()
//        val done = AppUtil.Paths.webenSourceDir(payload.sourceId).resolve("fetch_subtitle.done").exists()
//        logger.debug("FetchSubtitleExecutor.canSkip: sourceId=${payload.sourceId} done=$done")
//        return done
        TODO()
    }

    /**
     * 任务永久失败或取消时，将 WebenSource.analysisStatus 重置为 "failed"。
     * 覆写此回调而非在 executeWithSignals 各路径散落调用，保持业务状态更新的统一收口。
     */
    override suspend fun onTaskFailed(task: Task, result: ExecuteResult) {
//        val sourceId = runCatching {
//            Json.decodeFromString<Payload>(task.payload).sourceId
//        }.getOrNull() ?: return
//        runCatching {
//            WebenSourceService.repo.updateAnalysisStatus(
//                sourceId,
//                "failed"
//            )
//        }.onFailure { logger.warn("FetchSubtitleExecutor.onTaskFailed: 更新状态失败 sourceId=$sourceId: ${it.message}") }
//        logger.info("FetchSubtitleExecutor.onTaskFailed: sourceId=$sourceId → failed (errorType=${result.errorType})")
    }


    override suspend fun executeWithSignals(
        task: Task,
        cancelSignal: CompletableDeferred<Unit>,
        pauseResumeChannels: TaskPauseResumeChannels
    ): ExecuteResult {
        TODO("Not yet implemented")
    }
}


//
//import com.github.project_fredica.api.FredicaApi
//import com.github.project_fredica.api.post
//import com.github.project_fredica.apputil.AppUtil
//import com.github.project_fredica.apputil.BilibiliSubtitleUtil
//import com.github.project_fredica.apputil.buildValidJson
//import com.github.project_fredica.apputil.createLogger
//import com.github.project_fredica.apputil.json
//import com.github.project_fredica.db.AppConfig
//import com.github.project_fredica.db.AppConfigService
//import com.github.project_fredica.db.BilibiliSubtitleBodyCacheService
//import com.github.project_fredica.db.BilibiliSubtitleMetaCacheService
//import com.github.project_fredica.db.Task
//import com.github.project_fredica.db.TaskService
//import com.github.project_fredica.db.weben.WebenSourceService
//import com.github.project_fredica.python.PythonUtil
//import com.github.project_fredica.worker.ExecuteResult
//import com.github.project_fredica.worker.TaskPauseResumeChannels
//import com.github.project_fredica.worker.WebSocketTaskExecutor
//import kotlinx.coroutines.CompletableDeferred
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.withContext
//import kotlinx.serialization.SerialName
//import kotlinx.serialization.Serializable
//import kotlinx.serialization.json.Json
//import kotlinx.serialization.json.jsonArray
//import kotlinx.serialization.json.jsonObject
//import kotlinx.serialization.json.jsonPrimitive
//import java.io.File
//import java.security.MessageDigest
//
///**
// * 获取视频字幕文本，优先使用 Bilibili 字幕轨，无字幕时兜底使用 Whisper ASR。
// *
// * ## Payload 格式
// * ```json
// * {
// *   "material_id":      "",              // 素材 ID（ASR 兜底时用于定位视频文件）
// * }
// * ```
// *
// * ## 字幕轨优先级
// * 1. 官方中文字幕（lan="zh-CN"）
// * 2. Bilibili AI 字幕（lan 含 "ai"）
// * 3. 任意其他字幕轨
// * 4. 无字幕 → faster whisper ASR 兜底（需 material_id 定位视频文件）
// *
// * ## ASR 兜底（SHA-256 哈希缓存）
// * 缓存目录：`{appDataDir}/weben/asr_cache/{sha256}/`
// * 若缓存存在则直接复用，避免重复转录。
// *
// * ## 跳过机制（canSkip）
// * 检测 `{webenSourceDir}/fetch_subtitle.done` 是否存在。
// */
//object FetchSubtitleExecutor : WebSocketTaskExecutor() {
//    override val taskType = "FETCH_SUBTITLE"
//    private val logger = createLogger()
//
//    @Serializable
//    private data class Payload(
//        @SerialName("material_id") val materialId: String? = null,
//    )
//
//    override suspend fun canSkip(task: Task): Boolean {
//        val payload = runCatching { Json.decodeFromString<Payload>(task.payload) }.getOrNull() ?: return false
//        val done = AppUtil.Paths.webenSourceDir(payload.sourceId).resolve("fetch_subtitle.done").exists()
//        logger.debug("FetchSubtitleExecutor.canSkip: sourceId=${payload.sourceId} done=$done")
//        return done
//    }
//
//    /**
//     * 任务永久失败或取消时，将 WebenSource.analysisStatus 重置为 "failed"。
//     * 覆写此回调而非在 executeWithSignals 各路径散落调用，保持业务状态更新的统一收口。
//     */
//    override suspend fun onTaskFailed(task: Task, result: ExecuteResult) {
//        val sourceId = runCatching {
//            Json.decodeFromString<Payload>(task.payload).sourceId
//        }.getOrNull() ?: return
//        runCatching {
//            WebenSourceService.repo.updateAnalysisStatus(
//                sourceId,
//                "failed"
//            )
//        }.onFailure { logger.warn("FetchSubtitleExecutor.onTaskFailed: 更新状态失败 sourceId=$sourceId: ${it.message}") }
//        logger.info("FetchSubtitleExecutor.onTaskFailed: sourceId=$sourceId → failed (errorType=${result.errorType})")
//    }
//
//    override suspend fun executeWithSignals(
//        task: Task,
//        cancelSignal: CompletableDeferred<Unit>,
//        pauseResumeChannels: TaskPauseResumeChannels,
//    ): ExecuteResult = withContext(Dispatchers.IO) {
//        val payload = try {
//            Json.decodeFromString<Payload>(task.payload)
//        } catch (e: Throwable) {
//            logger.error("FetchSubtitleExecutor: payload 解析失败，taskId=${task.id}: ${e.message}")
//            return@withContext ExecuteResult(error = "Invalid payload: ${e.message}", errorType = "PAYLOAD_ERROR")
//        }
//
//        val sourceDir = AppUtil.Paths.webenSourceDir(payload.sourceId)
//        val outputFile = File(payload.outputTextPath)
//        outputFile.parentFile?.mkdirs()
//        logger.info("FetchSubtitleExecutor: outputFile=${outputFile.absolutePath} [taskId=${task.id}]")
//
//        // Task1 是流水线第一步：此时将来源置为 analyzing（原来只有 WebenConceptExtractExecutor 才设置，
//        // 导致 FetchSubtitle 执行期间来源仍显示 pending）
//        runCatching {
//            WebenSourceService.repo.updateAnalysisStatus(
//                payload.sourceId,
//                "analyzing"
//            )
//        }.onFailure { logger.warn("FetchSubtitleExecutor: 设置 analyzing 状态失败 sourceId=${payload.sourceId}: ${it.message}") }
//
//        // 1. 获取字幕元信息（走缓存+锁保护）
//        val cfg = AppConfigService.repo.getConfig()
//        logger.info("FetchSubtitleExecutor: 请求字幕元信息 bvid=${payload.bvid} pageIndex=${payload.pageIndex} [taskId=${task.id}]")
//        val metaRaw = try {
//            BilibiliSubtitleMetaCacheService.fetchBilibiliSubtitleMeta(
//                payload.bvid,
//                payload.pageIndex,
//                isUpdate = false,
//                cfg
//            )
//        } catch (e: Throwable) {
//            logger.error("FetchSubtitleExecutor: 获取字幕元信息失败 [taskId=${task.id}]: ${e.message}")
//            return@withContext ExecuteResult(
//                error = "获取字幕元信息失败: ${e.message}", errorType = "SUBTITLE_META_ERROR"
//            )
//        }
//
//        logger.debug("FetchSubtitleExecutor: 获取字幕元信息响应完成 [taskId=${task.id}]: $metaRaw")
//        val metaParseRes = BilibiliSubtitleUtil.parseMateRaw(metaRaw)
//        if (metaParseRes.isFailure) {
//            logger.error("FetchSubtitleExecutor: 获取字幕元信息解析 json 失败 [taskId=${task.id}]: error is ${metaParseRes.exceptionOrNull()} , metaRaw is $metaRaw")
//            return@withContext ExecuteResult(
//                error = "获取字幕元信息解析 json 失败: ${metaParseRes.exceptionOrNull()?.message}",
//                errorType = "SUBTITLE_META_JSON_PARSE_ERROR"
//            )
//        }
//        val meta = metaParseRes.getOrThrow()
//        if (meta.code != 0) {
//            logger.debug("FetchSubtitleExecutor: 获取字幕元信息失败 [taskId=${task.id}] - code 不为 0: $metaRaw")
//            return@withContext ExecuteResult(
//                error = "获取字幕元信息失败，code!=0，metaRaw is $metaRaw", errorType = "SUBTITLE_META_ERROR"
//            )
//        }
//        // 2. 解析字幕列表，选最佳字幕轨
//        val tracks = meta.subtitles.orEmpty()
//        // 优先级：官方 zh-CN > AI（含 "ai"）> 其他
//        val bestSubtitle =
//            tracks.filter { it.subtitleUrl !== null }.firstOrNull { it.lan == "zh-CN" }
//                ?: tracks.firstOrNull { it.lan?.contains("ai") ?: false }
//                ?: tracks.firstOrNull()
//
//        // 3. 尝试下载字幕文本（若字幕轨存在但内容为空，同样兜底 ASR）
//        val downloadedText: String? = if (bestSubtitle != null) {
//            val bestSubtitleUrl = bestSubtitle.subtitleUrl
//                ?: throw IllegalStateException("BUG, bestSubtitle.url 为 null 的情况应该已经 filter 了, bestSubtitle is $bestSubtitle")
//            logger.info("FetchSubtitleExecutor: 使用字幕轨 lan=${bestSubtitle.lan} url=${bestSubtitleUrl} [taskId=${task.id}]")
//            val bodyRaw = try {
//                BilibiliSubtitleBodyCacheService.fetchBilibiliSubtitleBody(bestSubtitleUrl, isUpdate = false)
//            } catch (e: Throwable) {
//                logger.error("FetchSubtitleExecutor: 获取字幕内容失败 [taskId=${task.id}]: ${e.message}")
//                return@withContext ExecuteResult(
//                    error = "获取字幕内容失败: ${e.message}", errorType = "SUBTITLE_BODY_ERROR"
//                )
//            }
//            val text = extractSubtitleText(bodyRaw)
//            text.ifBlank {
//                logger.warn("FetchSubtitleExecutor: 字幕轨 lan=${bestSubtitle.lan} 内容为空，将兜底 ASR [taskId=${task.id}]")
//                null
//            }
//        } else null
//
//        val textSource: String
//        if (downloadedText != null) {
//            // 主路径：字幕有效
//            outputFile.writeText(downloadedText)
//            textSource = if (bestSubtitle?.lan?.contains("ai") ?: false) "ai_subtitle" else "subtitle"
//            logger.info("FetchSubtitleExecutor: 字幕文本写入完成 chars=${downloadedText.length} source=$textSource [taskId=${task.id}]")
//        } else {
//            // 兜底路径：Whisper ASR（无字幕轨 或 字幕内容为空）
//            val fallbackReason = if (bestSubtitle == null) "无字幕轨" else "字幕内容为空"
//            val materialId = payload.materialId
//            if (materialId.isNullOrBlank()) {
//                logger.warn("FetchSubtitleExecutor: $fallbackReason 且无 material_id，无法 ASR 兜底 [taskId=${task.id}]")
//                outputFile.writeText("")
//                textSource = "none"
//            } else {
//                // ASR 模型未配置时提前返回，等待用户配置
//                if (cfg.fasterWhisperModel.isBlank()) {
//                    logger.warn("FetchSubtitleExecutor: $fallbackReason 且 ASR 模型未配置 [taskId=${task.id}]")
//                    return@withContext ExecuteResult(
//                        error = "ASR 模型未配置，请先运行兼容性评估并选择模型",
//                        errorType = "AWAITING_ASR_CONFIG",
//                    )
//                }
//                logger.info("FetchSubtitleExecutor: $fallbackReason，启动 ASR materialId=$materialId [taskId=${task.id}]")
//                val asrText = runAsrFallback(task, materialId, payload.sourceId, cancelSignal, pauseResumeChannels, cfg)
//                if (asrText == null) {
//                    return@withContext ExecuteResult(error = "用户已取消", errorType = "CANCELLED")
//                }
//                outputFile.writeText(asrText)
//                textSource = "asr"
//                logger.info("FetchSubtitleExecutor: ASR 完成 chars=${asrText.length} [taskId=${task.id}]")
//            }
//        }
//
//        // 写 done 标记
//        sourceDir.resolve("fetch_subtitle.done").writeText(textSource)
//
//        val charCount = if (outputFile.exists()) outputFile.readText().length else 0
//        ExecuteResult(result = buildValidJson {
//            kv("text_source", textSource)
//            kv("char_count", charCount)
//        }.str)
//    }
//
//    // -------------------------------------------------------------------------
//    // 字幕轨选择
//    // -------------------------------------------------------------------------
//
////    private fun selectBestSubtitle(onCodeNotZero: () -> Unit, metaRaw: String): SubtitleTrack? {
////        return try {
////            val rootParseRes = BilibiliSubtitleUtil.parseMateRaw(metaRaw)
////            if (rootParseRes.isFailure) {
////
////            }
////            val code = root
////            if (code != 0) {
////                onCodeNotZero()
////                return null
////            }
////
////            val subtitles = root["data"]?.jsonObject?.get("subtitles")?.jsonArray ?: return null
////
////            val tracks = subtitles.mapNotNull { el ->
////                val obj = el.jsonObject
////                val lan = obj["lan"]?.jsonPrimitive?.content ?: return@mapNotNull null
////                val url = obj["subtitle_url"]?.jsonPrimitive?.content ?: return@mapNotNull null
////                SubtitleTrack(lan, url)
////            }
////
////            // 优先级：官方 zh-CN > AI（含 "ai"）> 其他
////            tracks.firstOrNull { it.lan == "zh-CN" } ?: tracks.firstOrNull { it.lan.contains("ai") }
////            ?: tracks.firstOrNull()
////        } catch (e: Throwable) {
////            logger.warn("FetchSubtitleExecutor.selectBestSubtitle: 解析失败 ${e.message}")
////            null
////        }
////    }
//
//    private fun extractSubtitleText(bodyRaw: String): String {
//        return try {
//            val root = AppUtil.GlobalVars.json.parseToJsonElement(bodyRaw).jsonObject
//            val code = root["code"]?.jsonPrimitive?.content?.toIntOrNull()
//            if (code != 0) return ""
//            val body = root["data"]?.jsonObject?.get("body")?.jsonArray ?: return ""
//            body.joinToString("\n") { el ->
//                el.jsonObject["content"]?.jsonPrimitive?.content ?: ""
//            }.trim()
//        } catch (e: Throwable) {
//            logger.warn("FetchSubtitleExecutor.extractSubtitleText: 解析失败 ${e.message}")
//            ""
//        }
//    }
//
//    // -------------------------------------------------------------------------
//    // ASR 兜底（SHA-256 哈希缓存）
//    // -------------------------------------------------------------------------
//
//    private suspend fun runAsrFallback(
//        task: Task,
//        materialId: String,
//        sourceId: String,
//        cancelSignal: CompletableDeferred<Unit>,
//        pauseResumeChannels: TaskPauseResumeChannels,
//        cfg: AppConfig,
//    ): String? = withContext(Dispatchers.IO) {
//        // 1. 找视频文件
//        val mediaDir = AppUtil.Paths.materialMediaDir(materialId)
//        val videoFile = mediaDir.listFiles()?.firstOrNull { f -> f.extension in listOf("m4s", "mp4", "flv") } ?: run {
//            logger.warn("FetchSubtitleExecutor: 未找到视频文件 mediaDir=${mediaDir.absolutePath}")
//            return@withContext ""
//        }
//
//        // 2. 计算 SHA-256
//        val hash = computeSha256(videoFile)
//        val cacheDir = AppUtil.Paths.asrCacheDir(hash)
//        val transcribeDone = cacheDir.resolve("transcribe.done")
//
//        // 3. 检查整体缓存
//        if (transcribeDone.exists()) {
//            logger.info("FetchSubtitleExecutor: 命中 ASR 整体缓存 hash=$hash")
//            return@withContext mergeChunkTexts(cacheDir)
//        }
//
//        // 4. 提取并切段音频
//        val audioChunksDir = AppUtil.Paths.webenSourceDir(sourceId).resolve("audio_chunks").also { it.mkdirs() }
//        val extractDone = cacheDir.resolve("extract_split.done")
//
//        // 从 AppConfig 读取硬件加速类型（加速视频 demux 阶段，降低 CPU 开销）
//        val hwAccel = runCatching {
//            AppUtil.GlobalVars.json.parseToJsonElement(cfg.ffmpegProbeJson).jsonObject["selected_accel"]?.jsonPrimitive?.content
//                ?: ""
//        }.getOrDefault("")
//        logger.info("FetchSubtitleExecutor: ASR 使用 hwAccel=$hwAccel [taskId=${task.id}]")
//
//        val chunks: List<ChunkInfo> = if (extractDone.exists()) {
//            logger.info("FetchSubtitleExecutor: 命中 extract_split 缓存 hash=$hash")
//            parseChunkList(extractDone.readText())
//        } else {
//            TaskService.repo.updateProgress(task.id, 5)
//            WebenSourceService.syncProgressFromGraph(task.workflowRunId)
//            val extractParamJson = buildValidJson {
//                kv("video_path", videoFile.absolutePath)
//                kv("output_dir", audioChunksDir.absolutePath)
//                if (hwAccel.isNotBlank()) kv("hw_accel", hwAccel)
//            }.str
//            logger.info("FetchSubtitleExecutor: 开始提取切段音频 video=${videoFile.name} [taskId=${task.id}]")
//            val extractResult = PythonUtil.Py314Embed.PyUtilServer.websocketTask(
//                pth = "/audio/extract-split-audio-task",
//                paramJson = extractParamJson,
//                onProgress = { pct ->
//                    val taskPct = (5 + pct * 0.2).toInt()
//                    TaskService.repo.updateProgress(task.id, taskPct)
//                    WebenSourceService.syncProgressFromGraph(task.workflowRunId)
//                },
//                cancelSignal = cancelSignal,
//                pauseChannel = pauseResumeChannels.pause,
//                resumeChannel = pauseResumeChannels.resume,
//            ) ?: return@withContext null  // 取消
//
//            val parsedChunks = parseExtractResult(extractResult)
//            extractDone.writeText(AppUtil.GlobalVars.json.encodeToString(parsedChunks.map {
//                mapOf(
//                    "path" to it.path, "index" to it.index.toString()
//                )
//            }))
//            parsedChunks
//        }
//
//        if (chunks.isEmpty()) {
//            logger.warn("FetchSubtitleExecutor: 无 audio chunk，返回空文本 [taskId=${task.id}]")
//            return@withContext ""
//        }
//
//        // 5. 逐段转录
//        for ((i, chunk) in chunks.withIndex()) {
//            val chunkCache = cacheDir.resolve("chunk_${i.toString().padStart(4, '0')}.json")
//            if (chunkCache.exists()) {
//                logger.debug("FetchSubtitleExecutor: chunk $i 命中缓存，跳过转录")
//                continue
//            }
//
//            if (cancelSignal.isCompleted) return@withContext null
//
//            logger.info("FetchSubtitleExecutor: 转录 chunk $i/${chunks.size} path=${chunk.path} [taskId=${task.id}]")
//            val transcribeParam = buildValidJson {
//                kv("audio_path", chunk.path)
//                kv("language", "zh")
//                kv("model_name", cfg.fasterWhisperModel.ifBlank { "large-v3" })
//                kv("device", cfg.fasterWhisperDevice)
//                kv("compute_type", cfg.fasterWhisperComputeType)
//                if (cfg.fasterWhisperModelsDir.isNotBlank()) kv("models_dir", cfg.fasterWhisperModelsDir)
//                if (cfg.fasterWhisperInitJson != "{}") kv("whisper_init_extra", cfg.fasterWhisperInitJson)
//            }.str
//
//            val transcribeResult = PythonUtil.Py314Embed.PyUtilServer.websocketTask(
//                pth = "/audio/transcribe-chunk-task",
//                paramJson = transcribeParam,
//                onProgress = { pct ->
//                    val base = 25 + i * 75 / chunks.size
//                    val step = 75 / chunks.size
//                    val taskPct = base + pct * step / 100
//                    TaskService.repo.updateProgress(task.id, taskPct)
//                    WebenSourceService.syncProgressFromGraph(task.workflowRunId)
//                },
//                cancelSignal = cancelSignal,
//                pauseChannel = pauseResumeChannels.pause,
//                resumeChannel = pauseResumeChannels.resume,
//            ) ?: return@withContext null  // 取消
//
//            chunkCache.writeText(transcribeResult)
//        }
//
//        // 6. 合并并写 done
//        val mergedText = mergeChunkTexts(cacheDir)
//        transcribeDone.writeText(chunks.size.toString())
//        mergedText
//    }
//
//    private fun computeSha256(file: File): String {
//        val md = MessageDigest.getInstance("SHA-256")
//        file.inputStream().use { input ->
//            val buf = ByteArray(8192)
//            var n: Int
//            while (input.read(buf).also { n = it } != -1) {
//                md.update(buf, 0, n)
//            }
//        }
//        return md.digest().joinToString("") { "%02x".format(it) }
//    }
//
//    private data class ChunkInfo(val index: Int, val path: String)
//
//    private fun parseExtractResult(resultJson: String): List<ChunkInfo> {
//        return try {
//            val root = AppUtil.GlobalVars.json.parseToJsonElement(resultJson).jsonObject
//            val chunks = root["chunks"]?.jsonArray ?: return emptyList()
//            chunks.mapNotNull { el ->
//                val obj = el.jsonObject
//                val path = obj["path"]?.jsonPrimitive?.content ?: return@mapNotNull null
//                val idx = obj["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
//                ChunkInfo(idx, path)
//            }.sortedBy { it.index }
//        } catch (e: Throwable) {
//            logger.warn("FetchSubtitleExecutor.parseExtractResult: 解析失败 ${e.message}")
//            emptyList()
//        }
//    }
//
//    private fun parseChunkList(json: String): List<ChunkInfo> {
//        return try {
//            val arr = AppUtil.GlobalVars.json.parseToJsonElement(json).jsonArray
//            arr.mapNotNull { el ->
//                val obj = el.jsonObject
//                val path = obj["path"]?.jsonPrimitive?.content ?: return@mapNotNull null
//                val idx = obj["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
//                ChunkInfo(idx, path)
//            }.sortedBy { it.index }
//        } catch (e: Throwable) {
//            logger.warn("FetchSubtitleExecutor.parseChunkList: 解析失败 ${e.message}")
//            emptyList()
//        }
//    }
//
//    private fun mergeChunkTexts(cacheDir: File): String {
//        val texts = mutableListOf<String>()
//        var i = 0
//        while (true) {
//            val chunkFile = cacheDir.resolve("chunk_${i.toString().padStart(4, '0')}.json")
//            if (!chunkFile.exists()) break
//            try {
//                val obj = AppUtil.GlobalVars.json.parseToJsonElement(chunkFile.readText()).jsonObject
//                val text = obj["text"]?.jsonPrimitive?.content ?: ""
//                if (text.isNotBlank()) texts.add(text)
//            } catch (_: Throwable) {
//            }
//            i++
//        }
//        return texts.joinToString("\n")
//    }
//}
