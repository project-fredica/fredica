package com.github.project_fredica.bilibili_account_pool.model

import com.github.project_fredica.apputil.BilibiliApiPythonCredentialConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BilibiliAccount(
    val id: String,
    val label: String = "",
    @SerialName("is_anonymous") val isAnonymous: Boolean = false,
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("sessdata") override val bilibiliSessdata: String = "",
    @SerialName("bili_jct") override val bilibiliBiliJct: String = "",
    @SerialName("buvid3") override val bilibiliBuvid3: String = "",
    @SerialName("buvid4") override val bilibiliBuvid4: String = "",
    @SerialName("dedeuserid") override val bilibiliDedeuserid: String = "",
    @SerialName("ac_time_value") override val bilibiliAcTimeValue: String = "",
    @SerialName("proxy") override val bilibiliProxy: String = "",
    @SerialName("impersonate") override val bilibiliImpersonate: String = "chrome",
    @SerialName("rate_limit_sec") val rateLimitSec: Double = 1.0,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("last_ip") val lastIp: String = "",
    @SerialName("last_ip_checked_at") val lastIpCheckedAt: Long = 0,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
) : BilibiliApiPythonCredentialConfig
