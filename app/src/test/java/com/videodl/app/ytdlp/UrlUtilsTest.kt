package com.videodl.app.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析层的纯逻辑部分：平台识别、B 站分 P 处理、分享文案里的批量链接提取。
 *
 * 这里不覆盖 FormatBuilder —— youtubedl-android 的 VideoFormat 只有默认构造函数，
 * 字段无 setter，无法在 JVM 单测里造样例数据；画质分档由模拟器实跑验证。
 */
class UrlUtilsTest {

    @Test
    fun identifiesDouyin() {
        assertEquals(
            Platform.DOUYIN,
            UrlUtils.platformOf("https://www.douyin.com/video/7301234567890123456"),
        )
        assertEquals(Platform.DOUYIN, UrlUtils.platformOf("https://v.douyin.com/iABCdef/"))
        assertEquals(Platform.DOUYIN, UrlUtils.platformOf("https://www.iesdouyin.com/share/video/123"))
    }

    @Test
    fun identifiesBilibiliIncludingShortLink() {
        assertEquals(
            Platform.BILIBILI,
            UrlUtils.platformOf("https://www.bilibili.com/video/BV1xx411c7mD"),
        )
        assertEquals(Platform.BILIBILI, UrlUtils.platformOf("https://b23.tv/abc123"))
    }

    @Test
    fun identifiesXAndTwitter() {
        assertEquals(Platform.X, UrlUtils.platformOf("https://x.com/someone/status/1234567890"))
        assertEquals(
            Platform.X,
            UrlUtils.platformOf("https://twitter.com/someone/status/1234567890"),
        )
    }

    @Test
    fun unknownHostFallsBackToOther() {
        assertEquals(Platform.OTHER, UrlUtils.platformOf("https://example.com/video/1"))
    }

    @Test
    fun readsBilibiliPageFromUrl() {
        assertEquals(
            2,
            UrlUtils.bilibiliPageIndex("https://www.bilibili.com/video/BV1xx411c7mD?p=2"),
        )
        assertNull(UrlUtils.bilibiliPageIndex("https://www.bilibili.com/video/BV1xx411c7mD"))
        assertNull(
            UrlUtils.bilibiliPageIndex("https://www.bilibili.com/video/BV1xx411c7mD?p=abc"),
        )
    }

    @Test
    fun stripsBilibiliPageParam() {
        // yt-dlp 的 B 站 extractor 在 URL 带 ?p=N 时会取不到 cid（400），
        // 分 P 必须改用 --playlist-items，所以这里要把 p 参数去掉。
        assertEquals(
            "https://www.bilibili.com/video/BV1xx411c7mD",
            UrlUtils.withoutBilibiliPage("https://www.bilibili.com/video/BV1xx411c7mD?p=2"),
        )
        assertEquals(
            "https://www.bilibili.com/video/BV1xx411c7mD?t=10",
            UrlUtils.withoutBilibiliPage("https://www.bilibili.com/video/BV1xx411c7mD?t=10&p=3"),
        )
        assertEquals(
            "https://www.bilibili.com/video/BV1xx411c7mD",
            UrlUtils.withoutBilibiliPage("https://www.bilibili.com/video/BV1xx411c7mD"),
        )
        assertEquals(
            "https://b23.tv/abc123",
            UrlUtils.withoutBilibiliPage("https://b23.tv/abc123"),
        )
    }

    @Test
    fun extractsMultipleUrlsFromShareText() {
        val text = "看看这个 https://v.douyin.com/iABCdef/ 还有 " +
            "https://www.bilibili.com/video/BV1xx411c7mD?p=3 ，最后 https://x.com/u/status/123"
        val urls = UrlUtils.extractUrls(text)
        assertEquals(3, urls.size)
        assertEquals("https://v.douyin.com/iABCdef/", urls[0])
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD?p=3", urls[1])
        assertEquals("https://x.com/u/status/123", urls[2])
    }

    @Test
    fun deduplicatesRepeatedUrls() {
        val urls = UrlUtils.extractUrls("https://x.com/a/status/1 https://x.com/a/status/1")
        assertEquals(1, urls.size)
    }

    @Test
    fun trimsTrailingPunctuation() {
        assertEquals("https://x.com/a/status/1", UrlUtils.trimJunk("https://x.com/a/status/1，"))
        assertEquals("https://x.com/a/status/1", UrlUtils.trimJunk("https://x.com/a/status/1)."))
    }

    @Test
    fun ignoresTextWithoutAnyLink() {
        assertTrue(UrlUtils.extractUrls("这段文字里没有链接").isEmpty())
    }

    @Test
    fun parsesPlatformKeyRoundTrip() {
        Platform.entries.forEach { platform ->
            assertEquals(platform, Platform.fromKey(platform.key))
        }
        assertEquals(Platform.OTHER, Platform.fromKey("something-unknown"))
    }
}