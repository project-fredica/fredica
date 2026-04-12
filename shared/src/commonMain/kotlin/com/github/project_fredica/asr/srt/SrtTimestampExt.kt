package com.github.project_fredica.asr.srt

/** 便捷扩展：Double 秒数 → SRT 时间戳字符串 */
fun Double.toSrtTimestamp(): String = SrtTimestamp(this).format()
