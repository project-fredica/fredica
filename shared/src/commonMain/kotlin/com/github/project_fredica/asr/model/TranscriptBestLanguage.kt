package com.github.project_fredica.asr.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 跨 chunk 语言聚合结果，写入 transcript_best_language.json。
 *
 * 前端和路由可读取此文件获取最终语言判定。
 */
@Serializable
data class TranscriptBestLanguage(
    /** 多数投票选出的最终语言 */
    val language: String,
    /** 占比，如 0.8 表示 80% 的 chunk 检测为该语言 */
    val confidence: Double,
    /** chunkIndex → 该 chunk 检测到的语言 */
    @SerialName("chunk_languages") val chunkLanguages: Map<String, String>,
    /** 聚合时间 */
    @SerialName("determined_at") val determinedAt: String,
)
