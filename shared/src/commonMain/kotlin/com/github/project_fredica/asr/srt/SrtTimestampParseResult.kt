package com.github.project_fredica.asr.srt

import kotlin.jvm.JvmInline

/** [SrtTimestamp.parse] 的返回类型 */
sealed interface SrtTimestampParseResult {
    @JvmInline value class Ok(val timestamp: SrtTimestamp) : SrtTimestampParseResult

    /** 解析失败的具体原因 */
    sealed interface Err : SrtTimestampParseResult {
        val input: String

        /** 格式不符合 HH:MM:SS,mmm（分隔后段数 != 4） */
        data class InvalidFormat(override val input: String) : Err

        /** 小时段非数字 */
        data class InvalidHours(override val input: String, val segment: String) : Err

        /** 分钟段非数字 */
        data class InvalidMinutes(override val input: String, val segment: String) : Err

        /** 秒段非数字 */
        data class InvalidSeconds(override val input: String, val segment: String) : Err

        /** 毫秒段非数字 */
        data class InvalidMillis(override val input: String, val segment: String) : Err
    }
}
