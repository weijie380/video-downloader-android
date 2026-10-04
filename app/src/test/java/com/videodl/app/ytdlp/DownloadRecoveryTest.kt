package com.videodl.app.ytdlp

import org.junit.Assert.*
import org.junit.Test
import com.yausername.youtubedl_android.YoutubeDLRequest

class DownloadRecoveryTest {
    @Test fun retrySchedulesAreBothRetainedByAndroidRequestWrapper() {
        val request = YoutubeDLRequest("http://localhost/test")
        DownloadRecovery.configure(request)
        assertEquals(listOf("http:exp=1:8", "fragment:exp=1:8"), request.getArguments("--retry-sleep"))
        assertTrue(request.hasOption("--abort-on-unavailable-fragments"))
    }
    @Test fun directFilesKeepSelectedResolutionAndFallback() {
        val selector = DownloadRecovery.xSelector(720, "hls-123")
        assertTrue(selector.startsWith("best[protocol=https][height=720]/"))
        assertTrue(selector.endsWith("/hls-123"))
        assertFalse(selector.contains("height<=720"))
    }
    @Test fun retryTemporaryFragmentFailureButNotPermissionsOrDisk() {
        assertTrue(DownloadRecovery.shouldRefresh("ERROR: The downloaded file is empty"))
        assertTrue(DownloadRecovery.shouldRefresh("HTTP Error 403: Forbidden"))
        assertTrue(DownloadRecovery.shouldRefresh("Unable to download fragment: timed out"))
        assertFalse(DownloadRecovery.shouldRefresh("login required"))
        assertFalse(DownloadRecovery.shouldRefresh("HTTP Error 404"))
        assertFalse(DownloadRecovery.shouldRefresh("No space left on device"))
        assertFalse(DownloadRecovery.shouldRefresh("certificate_verify_failed"))
    }
    @Test fun fragmentCauseRemainsVisibleAndSignedUrlIsRedacted() {
        val message = YtDlpEngine.readable("""
            WARNING: Unable to download fragment: HTTP Error 403 from https://media.example/clip?token=secret
            ERROR: The downloaded file is empty
        """.trimIndent())
        assertTrue(message.contains("未收到有效"))
        assertTrue(message.contains("403"))
        assertFalse(message.contains("secret"))
    }
}
