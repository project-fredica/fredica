package com.github.project_fredica.bilibili_account_pool.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BilibiliAccountInfo(
    @SerialName("account_id") val accountId: String,
    val mid: String = "",
    val name: String = "",
    val face: String = "",
    val level: Int = 0,
    val sign: String = "",
    val coins: Double = 0.0,
    val fans: Int = 0,
    val following: Int = 0,
    @SerialName("queried_at") val queriedAt: Long = 0,
)
