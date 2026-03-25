package com.github.project_fredica.api.routes

import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.BilibiliSubtitleBodyCache
import com.github.project_fredica.db.BilibiliSubtitleBodyCacheRepo
import com.github.project_fredica.db.BilibiliSubtitleBodyCacheService
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * MaterialSubtitleContentRoute 单元测试。
 *
 * 覆盖场景：
 * 1. subtitle_url 为空时返回空正文
 * 2. bilibili_platform 正常聚合 body.content 为 text / word_count / segment_count
 * 3. 未知 source 不抛异常，返回空正文
 * 4. 非法 JSON 按 error-handling.md 返回 {error}
 */
class MaterialSubtitleContentRouteTest {

    @BeforeTest
    fun setup() {
        BilibiliSubtitleBodyCacheService.initialize(FakeBilibiliSubtitleBodyCacheRepo())
    }

    @Test
    fun `blank subtitle_url returns empty content`() = runBlocking {
        val result = MaterialSubtitleContentRoute.handler(
            """
                {"subtitle_url":"","source":"bilibili_platform","is_update":false}
            """.trimIndent()
        )
        val payload = result.str.loadJsonModel<MaterialSubtitleContentResponse>().getOrThrow()
        assertEquals("", payload.text)
        assertEquals(0, payload.wordCount)
        assertEquals(0, payload.segmentCount)
        assertEquals("bilibili_platform", payload.source)
    }

    @Test
    fun `bilibili source aggregates subtitle body`() = runBlocking {
        val result = MaterialSubtitleContentRoute.handler(
            """
                {"subtitle_url":"https://example.com/subtitle.json?foo=1","source":"bilibili_platform","is_update":false}
            """.trimIndent()
        )
        val payload = result.str.loadJsonModel<MaterialSubtitleContentResponse>().getOrThrow()
        assertEquals("第一行\n第二行", payload.text)
        assertEquals("第一行\n第二行".length, payload.wordCount)
        assertEquals(2, payload.segmentCount)
        assertEquals("bilibili_platform", payload.source)
        assertTrue(payload.subtitleUrl.contains("subtitle.json"))
    }

    @Test
    fun `unsupported source returns empty content`() = runBlocking {
        val result = MaterialSubtitleContentRoute.handler(
            """
                {"subtitle_url":"https://example.com/subtitle.json","source":"custom_source","is_update":false}
            """.trimIndent()
        )
        val payload = result.str.loadJsonModel<MaterialSubtitleContentResponse>().getOrThrow()
        assertEquals("", payload.text)
        assertEquals(0, payload.wordCount)
        assertEquals(0, payload.segmentCount)
        assertEquals("custom_source", payload.source)
    }

    @Test
    fun `invalid json returns error field`() = runBlocking {
        val result = MaterialSubtitleContentRoute.handler("not-json")
        val payload = result.str.loadJsonModel<Map<String, String>>().getOrThrow()
        val error = payload["error"]
        assertNotNull(error)
        assertTrue(error.isNotBlank())
    }
}

private class FakeBilibiliSubtitleBodyCacheRepo : BilibiliSubtitleBodyCacheRepo {
    override suspend fun insert(entry: BilibiliSubtitleBodyCache) = Unit

    override suspend fun queryBest(urlKey: String): BilibiliSubtitleBodyCache? {
        return BilibiliSubtitleBodyCache(
            urlKey = urlKey,
            queriedAt = System.currentTimeMillis() / 1000L,
            rawResult = """
                {
                  "code": 0,
                  "body": [
                    {"content": "第一行"},
                    {"content": "第二行"},
                    {"content": "  "}
                  ]
                }
            """.trimIndent(),
            isSuccess = true,
        )
    }
}
