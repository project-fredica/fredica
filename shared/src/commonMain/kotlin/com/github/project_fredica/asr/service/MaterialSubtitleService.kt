package com.github.project_fredica.asr.service

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.asr.model.AsrChunkMeta
import com.github.project_fredica.asr.model.AsrSubtitleItem
import com.github.project_fredica.asr.model.AsrSubtitleResponse
import com.github.project_fredica.asr.model.AsrSubtitleSegment
import com.github.project_fredica.asr.model.AsrTranscriptMeta
import com.github.project_fredica.asr.model.MaterialSubtitleContentResponse
import com.github.project_fredica.asr.model.MaterialSubtitleItem
import com.github.project_fredica.asr.model.BilibiliMaterialExtra
import com.github.project_fredica.asr.srt.ParseSrtBlocksResult
import com.github.project_fredica.asr.srt.SrtUtil
import com.github.project_fredica.db.MaterialVideoService
import java.io.File

// =============================================================================
// MaterialSubtitleService
// =============================================================================
//
// 素材字幕统一业务逻辑，供 Route 和 PromptVariableResolver 调用。
//
// 对外提供统一 API（scanAllSubtitleItems / fetchSubtitleContent / fetchSubtitleText），
// 内部区分 ASR 和 bilibili 两种字幕来源。
// =============================================================================

object MaterialSubtitleService {

    private val logger = createLogger { "MaterialSubtitleService" }

    // ── SRT 解析 ──────────────────────────────────────────────────────────────

    /** 解析 SRT 文本为分段列表（委托 [SrtUtil.parseSrtBlocks] 共享解析逻辑） */
    fun parseSrt(text: String): List<AsrSubtitleSegment> =
        when (val result = SrtUtil.parseSrtBlocks(text)) {
            is ParseSrtBlocksResult.Ok -> result.blocks.map { AsrSubtitleSegment(from = it.startSec, to = it.endSec, content = it.content) }
            is ParseSrtBlocksResult.Empty -> emptyList()
        }

    // ── Meta 读取 ─────────────────────────────────────────────────────────────

    /** 从 JSON 文件反序列化为指定类型，文件不存在或解析失败时返回 null */
    private inline fun <reified T> readJsonFile(file: File): T? {
        if (!file.exists()) return null
        return runCatching { file.readText().loadJsonModel<T>().getOrNull() }.getOrNull()
    }

    /** 读取 transcript.meta.json */
    fun readTranscriptMeta(file: File): AsrTranscriptMeta? = readJsonFile(file)

    /** 读取 chunk_XXXX.meta.json */
    fun readChunkMeta(file: File): AsrChunkMeta? = readJsonFile(file)

    // ── ASR 目录扫描 ──────────────────────────────────────────────────────────

    /**
     * 扫描 asr_results/{model_name}/ 下所有模型目录，返回字幕列表条目。
     *
     * 用于 MaterialSubtitleListRoute。
     */
    suspend fun scanAsrSubtitleItems(materialId: String): List<AsrSubtitleItem> {
        val mediaDir = AppUtil.Paths.materialMediaDir(materialId)
        val asrResultsDir = mediaDir.resolve("asr_results")
        if (!asrResultsDir.exists() || !asrResultsDir.isDirectory) return emptyList()

        val modelDirs = asrResultsDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        val items = mutableListOf<AsrSubtitleItem>()

        for (modelDir in modelDirs) {
            val modelName = modelDir.name
            val transcriptDone = modelDir.resolve("transcript.done")
            val transcriptSrt = modelDir.resolve("transcript.srt")
            val transcriptMetaFile = modelDir.resolve("transcript.meta.json")

            val asrModelSize: String? = readJsonFile<AsrTranscriptMeta>(transcriptMetaFile)?.modelSize

            if (transcriptDone.exists() && transcriptSrt.exists() && transcriptSrt.length() > 0) {
                items.add(
                    AsrSubtitleItem(
                        modelName = modelName,
                        modelSize = asrModelSize ?: modelName,
                        lanDoc = "ASR 识别 ($modelName)",
                        subtitleUrl = transcriptSrt.absolutePath,
                        queriedAt = transcriptSrt.lastModified() / 1000,
                        partial = false,
                    )
                )
            } else {
                val hasChunkSrt = modelDir.listFiles()?.any {
                    it.name.matches(Regex("chunk_\\d{4}\\.srt")) && it.length() > 0
                } == true
                if (hasChunkSrt) {
                    val chunkModelSize = asrModelSize ?: run {
                        val firstChunkMeta = modelDir.resolve("chunk_0000.meta.json")
                        readJsonFile<AsrChunkMeta>(firstChunkMeta)?.modelSize
                    }
                    items.add(
                        AsrSubtitleItem(
                            modelName = modelName,
                            modelSize = chunkModelSize ?: modelName,
                            lanDoc = "ASR 识别 ($modelName)（进行中）",
                            subtitleUrl = "",
                            queriedAt = System.currentTimeMillis() / 1000,
                            partial = true,
                        )
                    )
                }
            }
        }
        return items
    }

    // ── ASR 字幕详情 ──────────────────────────────────────────────────────────

    /**
     * 读取指定模型的 ASR 转录结果详情（分段字幕 + 元数据）。
     *
     * 用于 MaterialAsrSubtitleRoute。
     */
    suspend fun readAsrSubtitleDetail(materialId: String, modelSize: String): AsrSubtitleResponse {
        val mediaDir = AppUtil.Paths.materialMediaDir(materialId)
        val asrDir = mediaDir.resolve("asr_results").resolve(modelSize)
        val transcriptDone = asrDir.resolve("transcript.done")
        val transcriptSrt = asrDir.resolve("transcript.srt")
        val transcriptMetaFile = asrDir.resolve("transcript.meta.json")

        return if (transcriptDone.exists() && transcriptSrt.exists()) {
            val segments = parseSrt(transcriptSrt.readText())
            val meta = readJsonFile<AsrTranscriptMeta>(transcriptMetaFile)
            AsrSubtitleResponse(
                segments = segments,
                language = meta?.language,
                modelSize = meta?.modelSize,
                segmentCount = segments.size,
                totalChunks = meta?.totalChunks ?: 1,
                doneChunks = meta?.totalChunks ?: 1,
                partial = false,
            )
        } else {
            val chunkSrts = if (asrDir.exists()) {
                asrDir.listFiles()
                    ?.filter { it.name.matches(Regex("chunk_\\d{4}\\.srt")) && it.length() > 0 }
                    ?.sortedBy { it.name }
                    ?: emptyList()
            } else emptyList()

            if (chunkSrts.isEmpty()) {
                AsrSubtitleResponse()
            } else {
                val allSegments = chunkSrts.flatMap { parseSrt(it.readText()) }
                val doneCount = asrDir.listFiles()
                    ?.count { it.name.matches(Regex("chunk_\\d{4}\\.done")) } ?: 0
                val chunkMeta = readJsonFile<AsrChunkMeta>(asrDir.resolve("chunk_0000.meta.json"))
                val tMeta = readJsonFile<AsrTranscriptMeta>(transcriptMetaFile)
                AsrSubtitleResponse(
                    segments = allSegments,
                    language = chunkMeta?.language ?: tMeta?.language,
                    modelSize = chunkMeta?.modelSize ?: tMeta?.modelSize,
                    segmentCount = allSegments.size,
                    totalChunks = tMeta?.totalChunks ?: chunkSrts.size,
                    doneChunks = doneCount,
                    partial = true,
                )
            }
        }
    }

    // ── ASR 字幕内容读取 ──────────────────────────────────────────────────────

    /**
     * 读取 ASR SRT 文件的原始文本内容。
     *
     * @param srtFilePath transcript.srt 的绝对路径
     * @return SRT 文件内容，文件不存在时返回空字符串
     */
    fun readAsrSrtContent(srtFilePath: String): String {
        val srtFile = File(srtFilePath)
        return if (srtFile.exists()) srtFile.readText() else ""
    }

    // ── Bilibili 字幕扫描 ────────────────────────────────────────────────────────

    /**
     * 扫描 bilibili 来源素材的已缓存字幕列表。
     * 仅读取本地缓存，不触发网络请求。
     */
    suspend fun scanBilibiliSubtitleItems(materialId: String): List<MaterialSubtitleItem> {
        val material = MaterialVideoService.repo.findById(materialId) ?: return emptyList()
        if (material.sourceType != "bilibili") return emptyList()

        val extra = material.extra.loadJsonModel<BilibiliMaterialExtra>().getOrNull()
        val bvid = extra?.bvid
        if (bvid.isNullOrBlank()) {
            logger.debug("scanBilibiliSubtitleItems materialId=$materialId source=bilibili 但 extra.bvid 为空")
            return emptyList()
        }

        val cached = BilibiliSubtitleMetaCacheService.repo.queryBest(bvid, 0) ?: return emptyList()
        val meta = BilibiliSubtitleUtil.parseMateRaw(cached.rawResult).getOrNull() ?: return emptyList()

        return meta.subtitles?.mapNotNull { item ->
            val url = item.subtitleUrl ?: item.subtitleUrlV2 ?: return@mapNotNull null
            val lan = item.lan ?: return@mapNotNull null
            MaterialSubtitleItem(
                lan = lan,
                lanDoc = item.lanDoc ?: lan,
                source = "bilibili_platform",
                queriedAt = cached.queriedAt,
                subtitleUrl = url,
                type = item.type ?: 0,
            )
        } ?: emptyList()
    }

    // ── Bilibili 字幕内容 ────────────────────────────────────────────────────────

    /**
     * 获取 bilibili 字幕全文内容。
     * 通过 [BilibiliSubtitleBodyCacheService] 读取/缓存字幕 body。
     */
    suspend fun fetchBilibiliSubtitleContent(
        subtitleUrl: String,
        isUpdate: Boolean = false,
    ): MaterialSubtitleContentResponse {
        val raw = BilibiliSubtitleBodyCacheService.fetchBilibiliSubtitleBody(
            subtitleUrlFieldValue = subtitleUrl,
            isUpdate = isUpdate,
        )
        val text = BilibiliSubtitleUtil.parseSubtitleBodyText(raw) ?: ""
        val lines = text.lines().filter { it.isNotEmpty() }
        return MaterialSubtitleContentResponse(
            text = text,
            wordCount = text.length,
            segmentCount = lines.size,
            source = "bilibili_platform",
            subtitleUrl = subtitleUrl,
        )
    }

    // ── ASR 字幕内容（结构化） ───────────────────────────────────────────────────

    /**
     * 获取 ASR 字幕全文内容。
     *
     * @param srtFilePath transcript.srt 的绝对路径
     */
    fun fetchAsrSubtitleContent(srtFilePath: String): MaterialSubtitleContentResponse {
        val text = readAsrSrtContent(srtFilePath)
        val lines = text.lines().filter { it.isNotEmpty() }
        return MaterialSubtitleContentResponse(
            text = text,
            wordCount = text.length,
            segmentCount = lines.size,
            source = "asr",
            subtitleUrl = srtFilePath,
        )
    }

    // ── 统一接口 ─────────────────────────────────────────────────────────────────

    /**
     * 统一扫描所有来源的字幕列表（bilibili + ASR）。
     */
    suspend fun scanAllSubtitleItems(materialId: String): List<MaterialSubtitleItem> {
        val items = mutableListOf<MaterialSubtitleItem>()
        items.addAll(scanBilibiliSubtitleItems(materialId))
        scanAsrSubtitleItems(materialId).forEach { asr ->
            items.add(
                MaterialSubtitleItem(
                    lan = "asr",
                    lanDoc = asr.lanDoc,
                    source = "asr",
                    queriedAt = asr.queriedAt,
                    subtitleUrl = asr.subtitleUrl,
                    type = 1,
                    modelSize = asr.modelSize,
                    partial = asr.partial,
                )
            )
        }
        logger.debug("scanAllSubtitleItems materialId=$materialId total=${items.size} (bilibili=${items.count { it.source != "asr" }} asr=${items.count { it.source == "asr" }})")
        return items
    }

    /**
     * 统一获取字幕内容（按 source 分发到 bilibili 或 ASR）。
     */
    suspend fun fetchSubtitleContent(
        source: String,
        subtitleUrl: String,
        isUpdate: Boolean = false,
    ): MaterialSubtitleContentResponse {
        logger.debug("fetchSubtitleContent source=$source isUpdate=$isUpdate subtitleUrl=${subtitleUrl.take(120)}")
        return when (source) {
            "", "bilibili_platform" -> fetchBilibiliSubtitleContent(subtitleUrl, isUpdate)
            "asr" -> fetchAsrSubtitleContent(subtitleUrl)
            else -> {
                logger.debug("暂不支持的字幕来源 source=$source，返回空正文")
                MaterialSubtitleContentResponse(
                    text = "",
                    wordCount = 0,
                    segmentCount = 0,
                    source = source,
                    subtitleUrl = subtitleUrl,
                )
            }
        }
    }

    /**
     * 统一获取字幕全文（缓存优先），供 PromptVariableResolver 使用。
     *
     * 1. 先尝试 bilibili 本地缓存快速命中
     * 2. 未命中则扫描所有字幕列表，按 language 匹配后获取内容
     *
     * @param language 语言代码（如 "ai-zh"），null 取第一条
     */
    suspend fun fetchSubtitleText(materialId: String, language: String? = null): String? {
        // 快速路径：bilibili 缓存直接命中
        val fromCache = BilibiliSubtitleUtil.fetchSubtitleTextFromCache(materialId, language)
        if (fromCache != null) {
            logger.debug("fetchSubtitleText cache hit materialId=$materialId lan=$language length=${fromCache.length}")
            return fromCache
        }

        // 慢路径：扫描所有来源，按 language 匹配
        val items = scanAllSubtitleItems(materialId)
        if (items.isEmpty()) return null

        val item = if (language != null) {
            items.firstOrNull { it.lan == language } ?: return null
        } else {
            items.first()
        }

        val response = fetchSubtitleContent(item.source, item.subtitleUrl)
        return response.text.takeIf { it.isNotBlank() }
    }
}
