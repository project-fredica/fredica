package com.github.project_fredica.asr.service

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.asr.model.AsrChunkMeta
import com.github.project_fredica.asr.model.AsrSubtitleResponse
import com.github.project_fredica.asr.model.AsrSubtitleSegment
import com.github.project_fredica.asr.model.AsrTranscriptMeta
import com.github.project_fredica.asr.model.MaterialSubtitleContentResponse
import com.github.project_fredica.asr.model.MaterialSubtitleItem
import com.github.project_fredica.asr.model.BilibiliMaterialExtra
import com.github.project_fredica.asr.srt.ParseSrtBlocksResult
import com.github.project_fredica.asr.srt.SrtUtil
import com.github.project_fredica.db.MaterialMediaSpec
import com.github.project_fredica.db.MaterialVideoService
import java.io.File

// =============================================================================
// MaterialSubtitleService
// =============================================================================
//
// 素材字幕统一业务入口，供 Route 和 PromptVariableResolver 调用。
//
// 字幕 ID 格式（统一 subtitleId）：
//   bili.{lan}              — Bilibili 平台字幕（如 bili.ai-zh）
//   asr.{modelSize}         — ASR 识别字幕（如 asr.large-v3）
//   pp.{filenameStem}       — LLM 后处理字幕（如 pp.pp_1712000000_a3f2）
//   first                   — 特殊值：取第一条可用字幕
//
// 公开 API：
//   parseSubtitleId()        解析 subtitleId 为 (source, key)
//   getSubtitleText()        按 subtitleId 取字幕全文（供 PromptVariableResolver）
//   getSubtitleContent()     按 subtitleId 取结构化内容（供 Route）
//   scanAllSubtitleItems()   扫描所有来源的字幕列表
//   readAsrSubtitleDetail()  读取 ASR 详情（segments + meta）
//   fetchSubtitleContent()   按 source + subtitleUrl 取内容（供 Route）
//
// 内部实现细节（文件读写、SRT解析等）放在 Util 内部 object，
// 可通过 MaterialSubtitleService.Util.xxx 在测试中直接访问。
// =============================================================================

object MaterialSubtitleService {

    private val logger = createLogger { "MaterialSubtitleService" }

    // =========================================================================
    // Util — 内部工具方法（文件I/O、SRT解析、路径计算）
    // 测试通过 MaterialSubtitleService.Util.xxx 访问
    // =========================================================================

    object Util {

        /** 解析 SRT 文本为分段列表 */
        fun parseSrt(text: String): List<AsrSubtitleSegment> =
            when (val result = SrtUtil.parseSrtBlocks(text)) {
                is ParseSrtBlocksResult.Ok -> result.blocks.map {
                    AsrSubtitleSegment(from = it.startSec, to = it.endSec, content = it.content)
                }
                is ParseSrtBlocksResult.Empty -> emptyList()
            }

        /** 从 JSON 文件反序列化为指定类型，文件不存在或解析失败时返回 null */
        inline fun <reified T> readJsonFile(file: File): T? {
            if (!file.exists()) return null
            return runCatching { file.readText().loadJsonModel<T>().getOrNull() }.getOrNull()
        }

        /** 读取 transcript.meta.json */
        fun readTranscriptMeta(file: File): AsrTranscriptMeta? = readJsonFile(file)

        /** 读取 chunk_XXXX.meta.json */
        fun readChunkMeta(file: File): AsrChunkMeta? = readJsonFile(file)

        /** 读取 SRT 文件原始文本，文件不存在返回空串 */
        fun readSrtFile(file: File): String = if (file.exists()) file.readText() else ""

        /** 读取 SRT 文件原始文本（按绝对路径），文件不存在返回空串 */
        fun readSrtFile(absolutePath: String): String = readSrtFile(File(absolutePath))

        /** ASR transcript.srt 的 File 对象 */
        suspend fun asrSrtFile(materialId: String, modelSize: String): File =
            AppUtil.Paths.materialMediaDir(materialId)
                .resolve("asr_results").resolve(modelSize).resolve("transcript.srt")

        /** ASR 模型目录 */
        suspend fun asrModelDir(materialId: String, modelSize: String): File =
            AppUtil.Paths.materialMediaDir(materialId)
                .resolve("asr_results").resolve(modelSize)

        /** 后处理字幕目录 */
        suspend fun ppDir(materialId: String): File =
            AppUtil.Paths.materialMediaDir(materialId).resolve("asr_postprocess_result")

        /**
         * 将 SRT 文本转为 [MaterialSubtitleContentResponse]。
         * wordCount 按字符数计，segmentCount 按非空行数计。
         */
        fun srtTextToContentResponse(
            text: String,
            source: String,
            subtitleUrl: String,
        ): MaterialSubtitleContentResponse {
            val lines = text.lines().filter { it.isNotEmpty() }
            return MaterialSubtitleContentResponse(
                text = text,
                wordCount = text.length,
                segmentCount = lines.size,
                source = source,
                subtitleUrl = subtitleUrl,
            )
        }
    }

    // =========================================================================
    // 统一字幕 ID 解析
    // =========================================================================

    /**
     * 解析统一字幕 ID，返回 (source, key)。
     *
     * 格式：`{source}.{key}` 或特殊值 `first`。
     *
     * | subtitleId          | source | key           |
     * |---------------------|--------|---------------|
     * | `bili.ai-zh`        | bili   | ai-zh         |
     * | `asr.large-v3`      | asr    | large-v3      |
     * | `pp.pp_1712_a3f2`   | pp     | pp_1712_a3f2  |
     * | `first`             | first  | first         |
     */
    fun parseSubtitleId(subtitleId: String): Pair<String, String> {
        if (subtitleId == "first") return "first" to "first"
        val dot = subtitleId.indexOf('.')
        if (dot < 0) return "unknown" to subtitleId
        return subtitleId.take(dot) to subtitleId.substring(dot + 1)
    }

    // =========================================================================
    // 统一字幕文本获取（PromptVariableResolver 主入口）
    // =========================================================================

    /**
     * 按统一 subtitleId 获取字幕全文，供 PromptVariableResolver 使用。
     *
     * - `first`：取 scanAllSubtitleItems 的第一条
     * - `bili.{lan}`：取对应 Bilibili 字幕轨
     * - `asr.{modelSize}`：取对应 ASR transcript.srt
     * - `pp.{stem}`：取对应后处理 SRT 文件
     *
     * @return 字幕全文，无内容时返回 null
     */
    suspend fun getSubtitleText(materialId: String, subtitleId: String): String? {
        val (source, key) = parseSubtitleId(subtitleId)
        return when (source) {
            "first" -> fetchFirstSubtitleText(materialId)
            "bili" -> fetchBilibiliSubtitleTextByLan(materialId, key)
            "asr" -> {
                val text = Util.readSrtFile(Util.asrSrtFile(materialId, key))
                text.takeIf { it.isNotBlank() }
            }
            "pp" -> {
                val file = Util.ppDir(materialId).resolve("$key.srt")
                val text = Util.readSrtFile(file)
                text.takeIf { it.isNotBlank() }
            }
            else -> {
                logger.warn("getSubtitleText 未知 subtitleId 来源 source=$source subtitleId=$subtitleId")
                null
            }
        }
    }

    // =========================================================================
    // 统一字幕内容获取（按 subtitleId，供 Route 使用）
    // =========================================================================

    /**
     * 按统一 subtitleId 获取结构化字幕内容（含分段数、字数等元数据）。
     *
     * @return 内容响应，subtitleId 无法解析时返回 null
     */
    suspend fun getSubtitleContent(
        materialId: String,
        subtitleId: String,
        isUpdate: Boolean = false,
    ): MaterialSubtitleContentResponse? {
        val (source, key) = parseSubtitleId(subtitleId)
        return when (source) {
            "first" -> {
                val items = scanAllSubtitleItems(materialId)
                val first = items.firstOrNull() ?: return null
                fetchSubtitleContent(first.source, first.subtitleUrl, isUpdate)
            }
            "bili" -> {
                val url = findBilibiliSubtitleUrl(materialId, key) ?: return null
                fetchBilibiliSubtitleContent(url, isUpdate)
            }
            "asr" -> {
                val srtFile = Util.asrSrtFile(materialId, key)
                val text = Util.readSrtFile(srtFile)
                Util.srtTextToContentResponse(text, "asr", srtFile.absolutePath)
            }
            "pp" -> {
                val file = Util.ppDir(materialId).resolve("$key.srt")
                val text = Util.readSrtFile(file)
                Util.srtTextToContentResponse(text, "asr_postprocess", file.absolutePath)
            }
            else -> null
        }
    }

    // =========================================================================
    // ASR 目录扫描
    // =========================================================================

    /**
     * 扫描 asr_results/{model_name}/ 下所有模型目录，返回字幕列表条目。
     */
    internal suspend fun scanAsrSubtitleItems(materialId: String): List<MaterialSubtitleItem> {
        val mediaDir = AppUtil.Paths.materialMediaDir(materialId)
        val asrResultsDir = mediaDir.resolve("asr_results")
        if (!asrResultsDir.exists() || !asrResultsDir.isDirectory) return emptyList()

        val modelDirs = asrResultsDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        val items = mutableListOf<MaterialSubtitleItem>()

        for (modelDir in modelDirs) {
            val modelName = modelDir.name
            val transcriptDone = modelDir.resolve("transcript.done")
            val transcriptSrt = modelDir.resolve("transcript.srt")
            val transcriptMetaFile = modelDir.resolve("transcript.meta.json")

            val asrModelSize: String? = Util.readJsonFile<AsrTranscriptMeta>(transcriptMetaFile)?.modelSize

            if (transcriptDone.exists() && transcriptSrt.exists() && transcriptSrt.length() > 0) {
                items.add(
                    MaterialSubtitleItem(
                        lan = "asr",
                        lanDoc = "ASR 识别 ($modelName)",
                        source = "asr",
                        queriedAt = transcriptSrt.lastModified() / 1000,
                        subtitleUrl = transcriptSrt.absolutePath,
                        type = 1,
                        modelSize = asrModelSize ?: modelName,
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
                        Util.readJsonFile<AsrChunkMeta>(firstChunkMeta)?.modelSize
                    }
                    items.add(
                        MaterialSubtitleItem(
                            lan = "asr",
                            lanDoc = "ASR 识别 ($modelName)（进行中）",
                            source = "asr",
                            queriedAt = System.currentTimeMillis() / 1000,
                            subtitleUrl = "",
                            type = 1,
                            modelSize = chunkModelSize ?: modelName,
                            partial = true,
                        )
                    )
                }
            }
        }
        return items
    }

    // =========================================================================
    // ASR 字幕详情
    // =========================================================================

    /**
     * 读取指定模型的 ASR 转录结果详情（分段字幕 + 元数据）。
     * 用于 MaterialAsrSubtitleRoute。
     */
    suspend fun readAsrSubtitleDetail(materialId: String, modelSize: String): AsrSubtitleResponse {
        val asrDir = Util.asrModelDir(materialId, modelSize)
        val transcriptDone = asrDir.resolve("transcript.done")
        val transcriptSrt = asrDir.resolve("transcript.srt")
        val transcriptMetaFile = asrDir.resolve("transcript.meta.json")

        return if (transcriptDone.exists() && transcriptSrt.exists()) {
            val segments = Util.parseSrt(transcriptSrt.readText())
            val meta = Util.readJsonFile<AsrTranscriptMeta>(transcriptMetaFile)
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
                val allSegments = chunkSrts.flatMap { Util.parseSrt(it.readText()) }
                val doneCount = asrDir.listFiles()
                    ?.count { it.name.matches(Regex("chunk_\\d{4}\\.done")) } ?: 0
                val chunkMeta = Util.readJsonFile<AsrChunkMeta>(asrDir.resolve("chunk_0000.meta.json"))
                val tMeta = Util.readJsonFile<AsrTranscriptMeta>(transcriptMetaFile)
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

    // =========================================================================
    // Bilibili 字幕
    // =========================================================================

    /**
     * 扫描 bilibili 来源素材的已缓存字幕列表。
     * 仅读取本地缓存，不触发网络请求。
     */
    private suspend fun scanBilibiliSubtitleItems(materialId: String): List<MaterialSubtitleItem> {
        val material = MaterialVideoService.repo.findById(materialId) ?: return emptyList()
        if (!MaterialMediaSpec.isBilibiliVideo(material.type, material.sourceType)) return emptyList()

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

    /** 查找 Bilibili 字幕 URL（按 lan 匹配），未找到返回 null */
    private suspend fun findBilibiliSubtitleUrl(materialId: String, lan: String): String? {
        val items = scanBilibiliSubtitleItems(materialId)
        return items.firstOrNull { it.lan == lan }?.subtitleUrl
    }

    /** 按 lan 获取 Bilibili 字幕全文 */
    private suspend fun fetchBilibiliSubtitleTextByLan(materialId: String, lan: String): String? {
        // 快速路径：bilibili 缓存快速命中
        val fromCache = BilibiliSubtitleUtil.fetchSubtitleTextFromCache(materialId, lan)
        if (fromCache != null) {
            logger.debug("fetchBilibiliSubtitleTextByLan cache hit materialId=$materialId lan=$lan length=${fromCache.length}")
            return fromCache
        }
        val url = findBilibiliSubtitleUrl(materialId, lan) ?: return null
        val resp = fetchBilibiliSubtitleContent(url, isUpdate = false)
        return resp.text.takeIf { it.isNotBlank() }
    }

    /**
     * 获取 bilibili 字幕全文内容（通过缓存服务）。
     */
    private suspend fun fetchBilibiliSubtitleContent(
        subtitleUrl: String,
        isUpdate: Boolean = false,
    ): MaterialSubtitleContentResponse {
        val raw = BilibiliSubtitleBodyCacheService.fetchBilibiliSubtitleBody(
            subtitleUrlFieldValue = subtitleUrl,
            isUpdate = isUpdate,
        )
        val text = BilibiliSubtitleUtil.parseSubtitleBodyText(raw) ?: ""
        return Util.srtTextToContentResponse(text, "bilibili_platform", subtitleUrl)
    }

    // =========================================================================
    // Postprocess 字幕扫描
    // =========================================================================

    /**
     * 扫描 asr_postprocess_result/ 下的 LLM 后处理字幕文件。
     * 文件名格式：pp_{timestamp}_{hash4}.srt
     */
    private suspend fun scanPostprocessSubtitleItems(materialId: String): List<MaterialSubtitleItem> {
        val ppDir = Util.ppDir(materialId)
        if (!ppDir.exists() || !ppDir.isDirectory) return emptyList()

        val srtFiles = ppDir.listFiles()
            ?.filter { it.name.endsWith(".srt") && it.length() > 0 }
            ?.sortedByDescending { it.lastModified() }
            ?: return emptyList()

        return srtFiles.map { file ->
            val ts = file.name.removePrefix("pp_").substringBefore("_").toLongOrNull()
                ?: (file.lastModified() / 1000)
            MaterialSubtitleItem(
                lan = "postprocess",
                lanDoc = "LLM 后处理",
                source = "asr_postprocess",
                queriedAt = ts,
                subtitleUrl = file.absolutePath,
                type = 1,
            )
        }
    }

    // =========================================================================
    // 统一列表扫描
    // =========================================================================

    /**
     * 统一扫描所有来源的字幕列表（bilibili + ASR + postprocess）。
     */
    suspend fun scanAllSubtitleItems(materialId: String): List<MaterialSubtitleItem> {
        val items = mutableListOf<MaterialSubtitleItem>()
        items.addAll(scanBilibiliSubtitleItems(materialId))
        items.addAll(scanAsrSubtitleItems(materialId))
        items.addAll(scanPostprocessSubtitleItems(materialId))
        logger.debug(
            "scanAllSubtitleItems materialId=$materialId total=${items.size}" +
                " (bilibili=${items.count { it.source == "bilibili_platform" }}" +
                " asr=${items.count { it.source == "asr" }}" +
                " postprocess=${items.count { it.source == "asr_postprocess" }})",
        )
        return items
    }

    // =========================================================================
    // 统一内容获取（按 source + subtitleUrl，供 Route 使用）
    // =========================================================================

    /**
     * 按 source + subtitleUrl 获取字幕内容。
     * 供 MaterialSubtitleContentRoute 使用（接收前端传来的 subtitle_url + source）。
     */
    suspend fun fetchSubtitleContent(
        source: String,
        subtitleUrl: String,
        isUpdate: Boolean = false,
    ): MaterialSubtitleContentResponse {
        logger.debug("fetchSubtitleContent source=$source isUpdate=$isUpdate subtitleUrl=${subtitleUrl.take(120)}")
        return when (source) {
            "", "bilibili_platform" -> fetchBilibiliSubtitleContent(subtitleUrl, isUpdate)
            "asr" -> {
                val text = Util.readSrtFile(subtitleUrl)
                Util.srtTextToContentResponse(text, "asr", subtitleUrl)
            }
            "asr_postprocess" -> {
                val text = Util.readSrtFile(subtitleUrl)
                Util.srtTextToContentResponse(text, "asr_postprocess", subtitleUrl)
            }
            else -> {
                logger.debug("暂不支持的字幕来源 source=$source，返回空正文")
                MaterialSubtitleContentResponse(
                    text = "", wordCount = 0, segmentCount = 0,
                    source = source, subtitleUrl = subtitleUrl,
                )
            }
        }
    }

    // =========================================================================
    // 内部辅助
    // =========================================================================

    /** 取第一条可用字幕的全文（first 特殊值处理） */
    private suspend fun fetchFirstSubtitleText(materialId: String): String? {
        // 快速路径：bilibili 缓存快速命中
        val fromCache = BilibiliSubtitleUtil.fetchSubtitleTextFromCache(materialId, null)
        if (fromCache != null) {
            logger.debug("fetchFirstSubtitleText cache hit materialId=$materialId length=${fromCache.length}")
            return fromCache
        }
        val items = scanAllSubtitleItems(materialId)
        if (items.isEmpty()) return null
        val first = items.first()
        val resp = fetchSubtitleContent(first.source, first.subtitleUrl)
        return resp.text.takeIf { it.isNotBlank() }
    }
}
