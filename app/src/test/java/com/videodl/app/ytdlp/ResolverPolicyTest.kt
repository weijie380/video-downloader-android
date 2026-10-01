package com.videodl.app.ytdlp

import org.junit.Assert.*
import org.junit.Test

/** 不访问 X。用离线输入验证回退条件及多视频帖地址。 */
class ResolverPolicyTest {
    @Test fun xTrackingParametersAreRemovedWithoutLosingVideoIndex() {
        assertEquals("https://twitter.com/u/status/123/video/2",
            UrlUtils.normalizeX("https://x.com/u/status/123/video/2?s=20&t=abc"))
        assertEquals("https://t.co/abc", UrlUtils.normalizeX("https://t.co/abc"))
    }

    @Test fun blockedPrimaryApiCanUsePublicSyndicationApi() {
        assertTrue(RequestPolicy.shouldRetryX("HTTP Error 403: Forbidden"))
        assertTrue(RequestPolicy.shouldRetryX("Unable to extract GraphQL data"))
        assertTrue(RequestPolicy.shouldRetryX("Connection timed out"))
    }

    @Test fun reportedTweetWithoutVideoStillTriesSyndication() {
        assertTrue(RequestPolicy.shouldRetryX(
            "ERROR: [twitter] 2104910972989460910: No video could be found in this tweet"))
        assertTrue(RequestPolicy.shouldRetryX("ERROR: No video formats found!"))
    }

    @Test fun noVideoDoesNotOverrideExplicitAccessRestrictions() {
        assertFalse(RequestPolicy.shouldRetryX("HTTP Error 404: No video could be found in this tweet"))
        assertFalse(RequestPolicy.shouldRetryX("Login required: No video could be found in this tweet"))
    }

    @Test fun unavailableContentAndDnsAreNotReportedAsApiFallbackSuccess() {
        assertFalse(RequestPolicy.shouldRetryX("HTTP Error 404: Not Found"))
        assertFalse(RequestPolicy.shouldRetryX("Private tweet: login required"))
        assertFalse(RequestPolicy.shouldRetryX("Unable to download webpage: Temporary failure in name resolution"))
        assertFalse(RequestPolicy.shouldRetryX("CERTIFICATE_VERIFY_FAILED"))
    }

    @Test fun shareJsonHandlesStringBracesEscapesAndFollowingScripts() {
        val json = """{"loaderData":{"page":{"desc":"}\\\"{","other":{}}}}"""
        assertEquals(json, SharePageJson.extract("<script>window._ROUTER_DATA=$json; alert('{}');</script>"))
    }

    @Test fun shareJsonAcceptsWhitespaceButRejectsTruncatedData() {
        assertEquals("{}", SharePageJson.extract("window._ROUTER_DATA  =  {};"))
        assertNull(SharePageJson.extract("window._ROUTER_DATA = {\"a\": {"))
        assertNull(SharePageJson.extract("no router data"))
    }

    @Test fun emptyFormatListIsNotAReadyDownload() {
        assertTrue(FormatBuilder.build(emptyList()).isEmpty())
    }

    @Test fun specificNetworkFailureDetailsRemainVisible() {
        assertTrue(YtDlpEngine.readable("ERROR: Temporary failure in name resolution").contains("DNS"))
        assertTrue(YtDlpEngine.readable("ERROR: connection timed out").contains("超时"))
        assertTrue(YtDlpEngine.readable("ERROR: CERTIFICATE_VERIFY_FAILED").contains("TLS"))
    }
}
