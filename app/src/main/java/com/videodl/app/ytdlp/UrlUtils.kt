package com.videodl.app.ytdlp

import java.net.URI

/** 支持解析的平台。第一版只处理公开可访问的普通视频。 */
enum class Platform(val key: String, val displayName: String) {
    DOUYIN("douyin", "抖音"),
    BILIBILI("bilibili", "B站"),
    X("x", "X"),
    OTHER("other", "其他");

    companion object {
        fun fromKey(key: String): Platform = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

object UrlUtils {

    private val URL_REGEX =
        Regex("""https?://[^\s"'<>（）()\[\]【】{}，。、；：！？]+""", RegexOption.IGNORE_CASE)

    private const val TRAILING_JUNK = ".,;:!?)]}，。、；：！？）】”’\"'"

    /** 从粘贴/分享的整段文本里提取链接，保持出现顺序并去重。 */
    fun extractUrls(text: String): List<String> {
        val seen = LinkedHashSet<String>()
        URL_REGEX.findAll(text).forEach { match ->
            val cleaned = trimJunk(match.value)
            if (cleaned.length > 8) seen.add(cleaned)
        }
        return seen.toList()
    }

    /** 去掉分享文案里黏在链接尾部的标点。 */
    fun trimJunk(raw: String): String {
        var value = raw.trim()
        while (value.isNotEmpty() && TRAILING_JUNK.indexOf(value.last()) >= 0) {
            value = value.dropLast(1)
        }
        return value
    }

    fun platformOf(url: String): Platform {
        val host = hostOf(url) ?: return Platform.OTHER
        return when {
            host == "douyin.com" || host.endsWith(".douyin.com") ||
                host == "iesdouyin.com" || host.endsWith(".iesdouyin.com") -> Platform.DOUYIN

            host == "bilibili.com" || host.endsWith(".bilibili.com") ||
                host == "b23.tv" || host.endsWith(".b23.tv") -> Platform.BILIBILI

            host == "x.com" || host.endsWith(".x.com") ||
                host == "twitter.com" || host.endsWith(".twitter.com") ||
                host == "t.co" || host.endsWith(".t.co") -> Platform.X

            else -> Platform.OTHER
        }
    }

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host?.lowercase() }.getOrNull()?.removeSuffix(".")

    /** B 站链接里显式指定的分 P；未指定返回 null（按第 1P 处理）。 */
    fun bilibiliPageIndex(url: String): Int? {
        val query = url.substringAfter('?', "")
        if (query.isEmpty()) return null
        query.substringBefore('#').split('&').forEach { pair ->
            val kv = pair.split('=', limit = 2)
            if (kv.size == 2 && kv[0].equals("p", ignoreCase = true)) {
                return kv[1].toIntOrNull()?.takeIf { it > 0 }
            }
        }
        return null
    }

    /**
     * 去掉 B 站链接里的 p 参数。
     *
     * yt-dlp 的 B 站 extractor 在 URL 带 `?p=N` 时会取不到 cid（接口直接 400：
     * `Downloading video formats for cid None`），分 P 必须用 `--playlist-items N` 指定。
     * 因此这里把 URL 还原成干净的合集链接，分 P 交给命令行参数。
     */
    fun withoutBilibiliPage(url: String): String {
        if (isBilibiliShortLink(url)) return url

        val hashIndex = url.indexOf('#')
        val fragment = if (hashIndex >= 0) url.substring(hashIndex) else ""
        val head = if (hashIndex >= 0) url.substring(0, hashIndex) else url

        val queryIndex = head.indexOf('?')
        if (queryIndex < 0) return url

        val base = head.substring(0, queryIndex)
        val kept = head.substring(queryIndex + 1).split('&')
            .filter { it.isNotBlank() && !it.substringBefore('=').equals("p", ignoreCase = true) }

        return if (kept.isEmpty()) "$base$fragment" else "$base?${kept.joinToString("&")}$fragment"
    }

    /** 保留多视频帖的 /video/N，只移除分享追踪参数。短链交给 extractor 解开。 */
    fun normalizeX(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        if (uri.host?.lowercase() !in setOf("x.com", "www.x.com", "mobile.x.com",
                "twitter.com", "www.twitter.com", "mobile.twitter.com")) return url
        return "https://twitter.com${uri.rawPath.orEmpty()}"
    }

    fun isBilibiliShortLink(url: String): Boolean =
        hostOf(url)?.let { it == "b23.tv" || it.endsWith(".b23.tv") } == true
}