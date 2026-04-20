package com.github.project_fredica.db

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MaterialMediaSpecTest {

    // ── isBilibiliVideo ─────────────────────────────────────────────────────

    @Test
    fun `isBilibiliVideo - video + bilibili exact match`() {
        assertTrue(MaterialMediaSpec.isBilibiliVideo(MaterialType.VIDEO, "bilibili"))
    }

    @Test
    fun `isBilibiliVideo - video + bilibili subtypes`() {
        val subtypes = listOf(
            "bilibili_favorite",
            "bilibili_uploader",
            "bilibili_season",
            "bilibili_series",
            "bilibili_video_pages",
        )
        for (st in subtypes) {
            assertTrue(MaterialMediaSpec.isBilibiliVideo(MaterialType.VIDEO, st), "expected true for video + $st")
        }
    }

    @Test
    fun `isBilibiliVideo - non-video type returns false`() {
        assertFalse(MaterialMediaSpec.isBilibiliVideo(MaterialType.AUDIO, "bilibili"))
        assertFalse(MaterialMediaSpec.isBilibiliVideo(MaterialType.ARTICLE, "bilibili_favorite"))
        assertFalse(MaterialMediaSpec.isBilibiliVideo(MaterialType.IMAGE, "bilibili"))
        assertFalse(MaterialMediaSpec.isBilibiliVideo("", "bilibili"))
    }

    @Test
    fun `isBilibiliVideo - non-bilibili source returns false`() {
        assertFalse(MaterialMediaSpec.isBilibiliVideo(MaterialType.VIDEO, "local"))
        assertFalse(MaterialMediaSpec.isBilibiliVideo(MaterialType.VIDEO, "youtube"))
        assertFalse(MaterialMediaSpec.isBilibiliVideo(MaterialType.VIDEO, ""))
    }

    // ── toMediaSpec ─────────────────────────────────────────────────────────

    private fun makeMaterial(
        id: String = "bilibili_bvid__BV1test__P1",
        type: String = MaterialType.VIDEO,
        sourceType: String = "bilibili",
        sourceId: String = "BV1test",
    ) = MaterialVideo(
        id = id,
        type = type,
        title = "test",
        sourceType = sourceType,
        sourceId = sourceId,
        coverUrl = "",
        description = "",
        duration = 60,
        localVideoPath = "",
        localAudioPath = "",
        transcriptPath = "",
        extra = "{}",
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun `toMediaSpec - bilibili manual import`() = runBlocking {
        val spec = makeMaterial(sourceType = "bilibili").toMediaSpec()
        assertIs<MaterialMediaSpec.BilibiliVideo>(spec)
        assertEquals("BV1test", spec.bvid)
        assertEquals(1, spec.page)
        assertTrue(spec.needsDownload)
        Unit
    }

    @Test
    fun `toMediaSpec - bilibili_favorite`() = runBlocking {
        val spec = makeMaterial(sourceType = "bilibili_favorite").toMediaSpec()
        assertIs<MaterialMediaSpec.BilibiliVideo>(spec)
        assertTrue(spec.needsDownload)
        Unit
    }

    @Test
    fun `toMediaSpec - bilibili_uploader`() = runBlocking {
        val spec = makeMaterial(sourceType = "bilibili_uploader").toMediaSpec()
        assertIs<MaterialMediaSpec.BilibiliVideo>(spec)
        Unit
    }

    @Test
    fun `toMediaSpec - page extraction from id`() = runBlocking {
        val spec = makeMaterial(id = "bilibili_bvid__BV1abc__P3").toMediaSpec()
        assertIs<MaterialMediaSpec.BilibiliVideo>(spec)
        assertEquals(3, spec.page)
        Unit
    }

    @Test
    fun `toMediaSpec - page defaults to 1 when missing`() = runBlocking {
        val spec = makeMaterial(id = "bilibili_bvid__BV1abc").toMediaSpec()
        assertIs<MaterialMediaSpec.BilibiliVideo>(spec)
        assertEquals(1, spec.page)
        Unit
    }

    @Test
    fun `toMediaSpec - local source`() = runBlocking {
        val spec = makeMaterial(
            id = "local_video_001", sourceType = "local", sourceId = "file.mp4"
        ).toMediaSpec()
        assertIs<MaterialMediaSpec.LocalVideo>(spec)
        assertFalse(spec.needsDownload)
        Unit
    }

    @Test
    fun `toMediaSpec - non-video bilibili source resolves to LocalVideo`() = runBlocking {
        val spec = makeMaterial(
            type = MaterialType.AUDIO, sourceType = "bilibili"
        ).toMediaSpec()
        assertIs<MaterialMediaSpec.LocalVideo>(spec)
        Unit
    }
}
