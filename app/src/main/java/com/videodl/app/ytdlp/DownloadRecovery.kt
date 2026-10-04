package com.videodl.app.ytdlp

import com.yausername.youtubedl_android.YoutubeDLRequest

/** X 优先使用同一画质的完整 HTTP 文件，分片失败时重新提取一次媒体地址。 */
object DownloadRecovery {
    fun configure(request: YoutubeDLRequest) {
        request.addOption("--socket-timeout", 45)
        request.addOption("--retries", 5)
        request.addOption("--fragment-retries", 5)
        request.addOption("--retry-sleep", "http:exp=1:8")
        request.addOption("--retry-sleep", "fragment:exp=1:8")
        request.addOption("--abort-on-unavailable-fragments")
    }

    fun xSelector(height: Int, fallback: String): String {
        val size = if (height > 0) "[height=$height]" else ""
        return "best[protocol=https]$size/best[protocol=http]$size/" +
            "bestvideo[protocol=https]$size+bestaudio/" +
            "bestvideo[protocol=http]$size+bestaudio/$fallback"
    }

    fun shouldRefresh(message: String?): Boolean {
        val text = message.orEmpty().lowercase()
        if (listOf("private", "login required", "not authorized", "http error 404",
                "certificate_verify_failed", "name resolution", "no space left")
                .any { it in text }) return false
        return listOf("downloaded file is empty", "http error 403", "http error 410",
            "timed out", "connection reset", "unable to download", "fragment not found",
            "did not get any data blocks", "downloaded data is too short")
            .any { it in text }
    }
}
