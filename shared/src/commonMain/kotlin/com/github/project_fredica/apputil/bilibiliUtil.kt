package com.github.project_fredica.apputil

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject

interface BilibiliApiPythonCredentialConfig {
    val bilibiliSessdata: String
    val bilibiliBiliJct: String
    val bilibiliBuvid3: String
    val bilibiliDedeuserid: String
    val bilibiliAcTimeValue: String
    val bilibiliBuvid4: String
    val bilibiliProxy: String
}

@Serializable
data class BilibiliSubtitleMeta(
    val code: Int?,
    val message: String?,
    @SerialName("allow_submit")
    val allowSubmit: Boolean?,
    val subtitles: List<BilibiliSubtitleMetaSubtitleItem>?
)

@Serializable
data class BilibiliSubtitleMetaSubtitleItem(
    val id: Long?,
    val lan: String?,
    @SerialName("lan_doc")
    val lanDoc: String?,
    @SerialName("is_lock")
    val isLock: Boolean?,
    @SerialName("subtitle_url")
    val subtitleUrl: String?,
    @SerialName("subtitle_url_v2")
    val subtitleUrlV2: String?,
    val type: Int?,
    @SerialName("id_str")
    val idStr: String?,
    @SerialName("ai_type")
    val aiType: Int?,
    @SerialName("ai_status")
    val aiStatus: Int?,
)

object BilibiliSubtitleUtil {
    fun parseMateRaw(metaRaw: String): Result<BilibiliSubtitleMeta> {
        return metaRaw.loadJsonModel<BilibiliSubtitleMeta>()
    }
}