package com.github.project_fredica.asr.srt

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
        /** 解析 SRT 时间戳字符串 "HH:MM:SS,mmm" */
        fun parse(str: String): SrtTimestampParseResult {
            val parts = str.split(":", ",")
            if (parts.size != 4) return SrtTimestampParseResult.Err.InvalidFormat(str)
            val h = parts[0].toLongOrNull()
                ?: return SrtTimestampParseResult.Err.InvalidHours(str, parts[0])
            val m = parts[1].toLongOrNull()
                ?: return SrtTimestampParseResult.Err.InvalidMinutes(str, parts[1])
            val s = parts[2].toLongOrNull()
                ?: return SrtTimestampParseResult.Err.InvalidSeconds(str, parts[2])
            val ms = parts[3].toLongOrNull()
                ?: return SrtTimestampParseResult.Err.InvalidMillis(str, parts[3])
            return SrtTimestampParseResult.Ok(SrtTimestamp(h * 3600.0 + m * 60.0 + s + ms / 1000.0))
        }
    }
}
