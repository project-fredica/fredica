package com.github.project_fredica.asr.srt

/** 单个 SRT 字幕块 */
data class SrtBlock(val startSec: Double, val endSec: Double, val content: String)
