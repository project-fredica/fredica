package com.github.project_fredica.asr.model

import com.github.project_fredica.apputil.toFixed
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 转录结果分段，对应 SRT 中的一个字幕块（序号 + 时间范围 + 文本）。 */
data class TranscribeSegment(val start: Double, val end: Double, val text: String) {

    fun toPrettyString(): String {
        return "${SrtTimestamp(start).format()} -> ${SrtTimestamp(end).format()} $text"
    }

    private inline val startFixed get() = start.toFixed(1)
    private inline val endFixed get() = end.toFixed(1)

    fun toJsonLine(): String {
        return buildJsonObject {
            put("start", startFixed.toDouble())
            put("end", endFixed.toDouble())
            put("text", text)
        }.toString() + "\n"
    }

    companion object {
        /** 从 Segment 列表构建 SRT 文本 */
        fun buildSrt(segments: List<TranscribeSegment>): String = buildString {
            segments.forEachIndexed { i, seg ->
                appendLine(i + 1)
                appendLine("${SrtTimestamp(seg.start).format()} --> ${SrtTimestamp(seg.end).format()}")
                appendLine(seg.text)
                appendLine()
            }
        }

        /** 解析 SRT 文本为 Segment 列表（委托 [parseSrtBlocks] 共享解析逻辑） */
        fun parseSrt(text: String): List<TranscribeSegment> =
            parseSrtBlocks(text).map { (start, end, content) ->
                TranscribeSegment(start, end, content)
            }
    }
}
