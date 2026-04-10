package com.github.project_fredica.asr.model

/** ASR 目录扫描结果条目（内部中间类型，由 Route 转换为 MaterialSubtitleItem） */
data class AsrSubtitleItem(
    val modelName: String,
    val modelSize: String,
    val lanDoc: String,
    val subtitleUrl: String,
    val queriedAt: Long,
    val partial: Boolean,
)
