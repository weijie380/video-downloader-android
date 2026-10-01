package com.videodl.app.ytdlp

import java.net.URI

/** 仅转换官方播放接口的路径；不改动 CDN 签名、video_id 或第三方地址。 */
object DouyinVideoSource {
    fun withoutWatermark(raw: String): String? = runCatching {
        val uri = URI(raw)
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return null
        if (uri.path.contains("/playwm")) {
            if (uri.host.lowercase() !in setOf("aweme.snssdk.com", "www.iesdouyin.com", "www.douyin.com") ||
                uri.path !in setOf("/aweme/v1/playwm/", "/aweme/v1/playwm")) return null
            raw.replaceFirst("/aweme/v1/playwm", "/aweme/v1/play")
        } else if (uri.rawQuery.orEmpty().split('&').any { it == "watermark=1" || it == "lr=display_watermark" }) {
            null // 显式标记为水印的 CDN 地址不能当成原始流。
        } else raw
    }.getOrNull()
}
