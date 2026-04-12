package com.github.project_fredica.asr.srt

/** [String.parseSrtBlocks] 的返回类型 */
sealed interface ParseSrtBlocksResult {
    /** 解析成功，[blocks] 为非空列表 */
    data class Ok(val blocks: List<SrtBlock>) : ParseSrtBlocksResult

    /** 输入为空或不含任何有效块 */
    data object Empty : ParseSrtBlocksResult
}
