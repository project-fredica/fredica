package com.github.project_fredica.apputil

/**
 * Builds the deterministic DB primary key for a bilibili video page.
 *
 * Format: `bilibili_bvid__{bvid}__P{page}`
 * Example: `bilibili_bvid__BV1NK4y1V7M5__P1`
 *
 * `page` is the actual 1-based page number of this item.
 * Favourites-list imports always pass `page = 1` (the representative entry).
 */
fun bilibiliVideoId(bvid: String, page: Int = 1): String =
    "bilibili_bvid__${bvid}__P$page"

interface BilibiliApiPythonCredentialConfig {
    val bilibiliSessdata: String
    val bilibiliBiliJct: String
    val bilibiliBuvid3: String
    val bilibiliDedeuserid: String
    val bilibiliAcTimeValue: String
    val bilibiliBuvid4: String
    val bilibiliProxy: String
    val bilibiliImpersonate: String
}
