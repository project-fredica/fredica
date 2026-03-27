package com.github.project_fredica.apputil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BilibiliSubtitleUtilTest {
    @Test
    fun `BilibiliSubtitleBodyItem toSrtLines returns srt like triple`() {
        val item = BilibiliSubtitleBodyItem(
            content = "  你好，世界  ",
            from = 1.234,
            to = 5.678,
        )

        val result = item.toSrtLines(3)

        assertEquals(
            Triple(3, "00:00:01,234 --> 00:00:05,678", "你好，世界"),
            result,
        )
    }

    @Test
    fun `parseSubtitleBodyText returns srt like blocks`() {
        val bodyRaw = AppUtil.dumpJsonStr(
            BilibiliSubtitleBodyPayload(
                body = listOf(
                    BilibiliSubtitleBodyItem(content = " 第一行 ", from = 0.0, to = 1.5),
                    BilibiliSubtitleBodyItem(content = "", from = 1.5, to = 2.0),
                    BilibiliSubtitleBodyItem(content = "第二行", from = 2.0, to = 3.25),
                ),
            ),
        ).getOrThrow().str

        val text = BilibiliSubtitleUtil.parseSubtitleBodyText(bodyRaw)

        assertEquals(
            """
            1
            00:00:00,000 --> 00:00:01,500
            第一行

            3
            00:00:02,000 --> 00:00:03,250
            第二行
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `extractSubtitleUrlKey keeps raw percent encoding in path`() {
        val url = "//subtitle.bilibili.com/S%13%1BP.%1D%28%29X%2CR%5Ej%1F%25w%0E%02H%5EHO4%14%7B4%08K@%3C%7B%00M%0B%0A%1AM%08%056N6$%0C0&%02%1E?auth_key=abc"

        val key = BilibiliSubtitleUtil.extractSubtitleUrlKey(url)

        assertTrue(key.startsWith("subtitle.bilibili.com/S%13%1BP.%1D%28%29X%2CR%5Ej"))
        assertFalse(key.contains('\n'), "urlKey 不应包含换行")
        assertFalse(key.contains('\r'), "urlKey 不应包含回车")
        assertFalse(key.any { it.code < 32 || it.code == 127 }, "urlKey 不应包含控制字符")
    }

    @Test
    fun `extractSubtitleUrlKey strips expiring query params but keeps stable params`() {
        val url = "//subtitle.bilibili.com/subtitle.json?foo=1&auth_key=abc&wts=123&w_rid=456&e=789&bar=2"

        val key = BilibiliSubtitleUtil.extractSubtitleUrlKey(url)

        assertEquals("subtitle.bilibili.com/subtitle.json?foo=1&bar=2", key)
    }

    @Test
    fun `extractFirstSubtitleUrl prefers subtitle_url to match panel`() {
        val metaRaw = AppUtil.dumpJsonStr(
            BilibiliSubtitleMeta(
                code = 0,
                message = "ok",
                allowSubmit = false,
                subtitles = listOf(
                    BilibiliSubtitleMetaSubtitleItem(
                        id = 1L,
                        lan = "ai-zh",
                        lanDoc = "中文（自动）",
                        isLock = false,
                        subtitleUrl = "//aisubtitle.hdslb.com/path-ok",
                        subtitleUrlV2 = "//subtitle.bilibili.com/path-bad",
                        type = 1,
                        idStr = "1",
                        aiType = 1,
                        aiStatus = 1,
                    )
                ),
            )
        ).getOrThrow().str

        val url = BilibiliSubtitleUtil.extractFirstSubtitleUrl(metaRaw, "ai-zh")

        assertEquals("//aisubtitle.hdslb.com/path-ok", url)
    }
}
