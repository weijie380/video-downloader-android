package com.videodl.app.ytdlp

import org.junit.Assert.*
import org.junit.Test

class DouyinVideoSourceTest {
    @Test fun switchesOfficialEndpointWithoutChangingSignedQuery() {
        val raw = "https://aweme.snssdk.com/aweme/v1/playwm/?video_id=v0300&ratio=720p&token=a%2Fb&line=0"
        assertEquals(raw.replace("/playwm/", "/play/"), DouyinVideoSource.withoutWatermark(raw))
    }
    @Test fun preservesDirectOriginalStreamExactly() {
        val raw = "https://v3.douyinvod.com/video/tos/file/?token=a%2Fb%3D"
        assertEquals(raw, DouyinVideoSource.withoutWatermark(raw))
    }
    @Test fun refusesMarkedWatermarkAndForeignPlaywmEndpoints() {
        assertNull(DouyinVideoSource.withoutWatermark("https://example.com/aweme/v1/playwm/?video_id=1"))
        assertNull(DouyinVideoSource.withoutWatermark("https://v3.douyinvod.com/video/?lr=display_watermark"))
        assertNull(DouyinVideoSource.withoutWatermark("https://v3.douyinvod.com/video/?watermark=1"))
        assertNull(DouyinVideoSource.withoutWatermark("http://aweme.snssdk.com/aweme/v1/playwm/"))
    }
}
