package com.github.project_fredica.material_category

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.json
import com.github.project_fredica.material_category.model.MaterialCategorySyncPlatformIdentity
import com.github.project_fredica.material_category.model.MaterialCategorySyncPlatformIdentity.*
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MaterialCategorySyncPlatformIdentityTest {

    private val json = AppUtil.GlobalVars.json

    @Test
    fun bilibiliFavoriteSyncTypeAndPlatformId() {
        val fav = BilibiliFavorite(mediaId = 12345678)
        assertEquals("bilibili_favorite", fav.syncType)
        assertEquals("12345678", fav.platformId)
    }

    @Test
    fun bilibiliUploaderSyncTypeAndPlatformId() {
        val up = BilibiliUploader(mid = 456789)
        assertEquals("bilibili_uploader", up.syncType)
        assertEquals("456789", up.platformId)
    }

    @Test
    fun bilibiliSeasonSyncTypeAndPlatformId() {
        val season = BilibiliSeason(seasonId = 1001, mid = 456789)
        assertEquals("bilibili_season", season.syncType)
        assertEquals("456789:1001", season.platformId)
    }

    @Test
    fun bilibiliSeriesSyncTypeAndPlatformId() {
        val series = BilibiliSeries(seriesId = 2001, mid = 456789)
        assertEquals("bilibili_series", series.syncType)
        assertEquals("456789:2001", series.platformId)
    }

    @Test
    fun bilibiliVideoPagesSyncTypeAndPlatformId() {
        val pages = BilibiliVideoPages(bvid = "BV1xx411c7mD")
        assertEquals("bilibili_video_pages", pages.syncType)
        assertEquals("BV1xx411c7mD", pages.platformId)
    }

    @Test
    fun serializeAndDeserializeBilibiliFavorite() {
        val original: MaterialCategorySyncPlatformIdentity = BilibiliFavorite(mediaId = 99999)
        val jsonStr = json.encodeToString(original)
        val decoded = json.decodeFromString<MaterialCategorySyncPlatformIdentity>(jsonStr)
        assertIs<BilibiliFavorite>(decoded)
        assertEquals(99999, decoded.mediaId)
    }

    @Test
    fun serializeAndDeserializeBilibiliSeason() {
        val original: MaterialCategorySyncPlatformIdentity = BilibiliSeason(seasonId = 500, mid = 100)
        val jsonStr = json.encodeToString(original)
        val decoded = json.decodeFromString<MaterialCategorySyncPlatformIdentity>(jsonStr)
        assertIs<BilibiliSeason>(decoded)
        assertEquals(500, decoded.seasonId)
        assertEquals(100, decoded.mid)
    }

    @Test
    fun serializeAndDeserializeBilibiliVideoPages() {
        val original: MaterialCategorySyncPlatformIdentity = BilibiliVideoPages(bvid = "BV1abc")
        val jsonStr = json.encodeToString(original)
        val decoded = json.decodeFromString<MaterialCategorySyncPlatformIdentity>(jsonStr)
        assertIs<BilibiliVideoPages>(decoded)
        assertEquals("BV1abc", decoded.bvid)
    }

    @Test
    fun polymorphicJsonContainsTypeDiscriminator() {
        val fav: MaterialCategorySyncPlatformIdentity = BilibiliFavorite(mediaId = 1)
        val jsonStr = json.encodeToString(fav)
        assert(jsonStr.contains("bilibili_favorite")) { "JSON should contain type discriminator: $jsonStr" }
    }
}
