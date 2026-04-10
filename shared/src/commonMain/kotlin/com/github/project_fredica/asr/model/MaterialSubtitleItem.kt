package com.github.project_fredica.asr.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 素材字幕列表条目 */
@Serializable
data class MaterialSubtitleItem(
    val lan: String,
    @SerialName("lan_doc") val lanDoc: String,
    /** 字幕来源标识，如 "bilibili_platform" / "asr" */
    val source: String,
    /** 缓存写入时间（Unix 秒） */
    @SerialName("queried_at") val queriedAt: Long,
    /** 字幕内容 URL 或本地文件路径 */
    @SerialName("subtitle_url") val subtitleUrl: String,
    /** 0=人工字幕，1=AI 字幕 */
    val type: Int = 0,
    /** ASR 模型名称（如 "large-v3"），仅 source="asr" 时有值 */
    @SerialName("model_size") val modelSize: String? = null,
    /** 转录是否未完成（有 chunk_*.srt 但无 transcript.done） */
    val partial: Boolean = false,
)
