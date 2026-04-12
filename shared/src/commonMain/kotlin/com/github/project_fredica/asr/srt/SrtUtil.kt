package com.github.project_fredica.asr.srt

/** SRT 文本解析工具 */
object SrtUtil {

    /** SRT 时间行正则：匹配 "HH:MM:SS,mmm --> HH:MM:SS,mmm"（逗号或句点分隔毫秒） */
    private val SRT_TIME_LINE_REGEX = Regex(
        """(\d{2}:\d{2}:\d{2}[,\.]\d{3})\s*-->\s*(\d{2}:\d{2}:\d{2}[,\.]\d{3})"""
    )

    /**
     * 解析 SRT 文本为 [SrtBlock] 列表。
     *
     * 供 TranscribeSegment.parseSrt 和 MaterialSubtitleService.parseSrt 共用，
     * 避免两处各自维护正则和解析逻辑。
     */
    fun parseSrtBlocks(text: String): ParseSrtBlocksResult {
        val rawBlocks = text.trim().split(Regex("\\r?\\n\\r?\\n+"))
        val blocks = rawBlocks.mapNotNull { block ->
            val lines = block.trim().lines()
            if (lines.size < 2) return@mapNotNull null
            // 时间行可能在第 1 行（标准 SRT）或第 0 行（无序号）
            val timeLineIndex = lines.indexOfFirst { SRT_TIME_LINE_REGEX.containsMatchIn(it) }
            if (timeLineIndex < 0) return@mapNotNull null
            val match = SRT_TIME_LINE_REGEX.find(lines[timeLineIndex])!!
            val startSec = when (val r = SrtTimestamp.parse(match.groupValues[1].replace('.', ','))) {
                is SrtTimestampParseResult.Ok -> r.timestamp.seconds
                is SrtTimestampParseResult.Err -> return@mapNotNull null
            }
            val endSec = when (val r = SrtTimestamp.parse(match.groupValues[2].replace('.', ','))) {
                is SrtTimestampParseResult.Ok -> r.timestamp.seconds
                is SrtTimestampParseResult.Err -> return@mapNotNull null
            }
            val content = lines.drop(timeLineIndex + 1).joinToString("\n").trim()
            if (content.isEmpty()) return@mapNotNull null
            SrtBlock(startSec, endSec, content)
        }
        return if (blocks.isEmpty()) ParseSrtBlocksResult.Empty else ParseSrtBlocksResult.Ok(blocks)
    }
}
