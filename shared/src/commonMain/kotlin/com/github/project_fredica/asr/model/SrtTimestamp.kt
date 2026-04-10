package com.github.project_fredica.asr.model

import kotlin.jvm.JvmInline

/**
 * SRT 时间戳 value class，封装秒数。
 * 统一 SRT 格式化/解析逻辑，消除 TranscribeExecutor / MaterialSubtitleService / BilibiliSubtitleBodyItem 中的重复实现。
 */
@JvmInline
value class SrtTimestamp(val seconds: Double) {
    /** 格式化为 SRT 时间戳：HH:MM:SS,mmm */
    fun format(): String {
        val totalMillis = (seconds * 1000).toLong().coerceAtLeast(0L)
        val h = totalMillis / 3_600_000
        val m = (totalMillis % 3_600_000) / 60_000
        val s = (totalMillis % 60_000) / 1000
        val ms = totalMillis % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, ms)
    }

    override fun toString(): String = format()

    companion object {
        /** 解析 SRT 时间戳字符串 "HH:MM:SS,mmm" 为 SrtTimestamp */
        fun parse(str: String): SrtTimestamp {
            val parts = str.split(":", ",")
            if (parts.size != 4) return SrtTimestamp(0.0)
            val h = parts[0].toLongOrNull() ?: return SrtTimestamp(0.0)
            val m = parts[1].toLongOrNull() ?: return SrtTimestamp(0.0)
            val s = parts[2].toLongOrNull() ?: return SrtTimestamp(0.0)
            val ms = parts[3].toLongOrNull() ?: return SrtTimestamp(0.0)
            return SrtTimestamp(h * 3600.0 + m * 60.0 + s + ms / 1000.0)
        }
    }
}

/** 便捷扩展：Double 秒数 → SRT 时间戳字符串 */
internal fun Double.toSrtTimestamp(): String = SrtTimestamp(this).format()

// ── SRT 块解析工具 ──────────────────────────────────────────────────────────

/** SRT 时间行正则：匹配 "HH:MM:SS,mmm --> HH:MM:SS,mmm"（逗号或句点分隔毫秒） */
private val SRT_TIME_LINE_REGEX = Regex(
    """(\d{2}:\d{2}:\d{2}[,\.]\d{3})\s*-->\s*(\d{2}:\d{2}:\d{2}[,\.]\d{3})"""
)

/**
 * 解析 SRT 文本为 (startSec, endSec, text) 三元组列表。
 *
 * 供 [TranscribeSegment.parseSrt] 和 [com.github.project_fredica.asr.service.MaterialSubtitleService.parseSrt] 共用，
 * 避免两处各自维护正则和解析逻辑。
 */
fun parseSrtBlocks(text: String): List<Triple<Double, Double, String>> {
    val blocks = text.trim().split(Regex("\\r?\\n\\r?\\n+"))
    return blocks.mapNotNull { block ->
        val lines = block.trim().lines()
        if (lines.size < 2) return@mapNotNull null
        // 时间行可能在第 1 行（标准 SRT）或第 0 行（无序号）
        val timeLineIndex = lines.indexOfFirst { SRT_TIME_LINE_REGEX.containsMatchIn(it) }
        if (timeLineIndex < 0) return@mapNotNull null
        val match = SRT_TIME_LINE_REGEX.find(lines[timeLineIndex])!!
        val start = SrtTimestamp.parse(match.groupValues[1].replace('.', ',')).seconds
        val end = SrtTimestamp.parse(match.groupValues[2].replace('.', ',')).seconds
        val content = lines.drop(timeLineIndex + 1).joinToString("\n").trim()
        if (content.isEmpty()) return@mapNotNull null
        Triple(start, end, content)
    }
}
