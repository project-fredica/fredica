package com.github.project_fredica.material_category.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface MaterialCategorySyncPlatformIdentity {
    val syncType: String
    val platformId: String

    @Serializable
    @SerialName("bilibili_favorite")
    data class BilibiliFavorite(
        @SerialName("media_id") val mediaId: Long,
    ) : MaterialCategorySyncPlatformIdentity {
        override val syncType get() = "bilibili_favorite"
        override val platformId get() = mediaId.toString()
    }

    @Serializable
    @SerialName("bilibili_uploader")
    data class BilibiliUploader(
        val mid: Long,
    ) : MaterialCategorySyncPlatformIdentity {
        override val syncType get() = "bilibili_uploader"
        override val platformId get() = mid.toString()
    }

    @Serializable
    @SerialName("bilibili_season")
    data class BilibiliSeason(
        @SerialName("season_id") val seasonId: Long,
        val mid: Long,
    ) : MaterialCategorySyncPlatformIdentity {
        override val syncType get() = "bilibili_season"
        override val platformId get() = "$mid:$seasonId"
    }

    @Serializable
    @SerialName("bilibili_series")
    data class BilibiliSeries(
        @SerialName("series_id") val seriesId: Long,
        val mid: Long,
    ) : MaterialCategorySyncPlatformIdentity {
        override val syncType get() = "bilibili_series"
        override val platformId get() = "$mid:$seriesId"
    }

    @Serializable
    @SerialName("bilibili_video_pages")
    data class BilibiliVideoPages(
        val bvid: String,
    ) : MaterialCategorySyncPlatformIdentity {
        override val syncType get() = "bilibili_video_pages"
        override val platformId get() = bvid
    }
}
