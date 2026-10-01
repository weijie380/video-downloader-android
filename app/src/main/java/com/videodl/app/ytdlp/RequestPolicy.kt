package com.videodl.app.ytdlp

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.net.URI
import java.io.File

/** 解析与下载共用，避免只在预览阶段设置代理或备用接口。 */
object RequestPolicy {
    fun proxy(context: Context): String = context.getSharedPreferences("network", 0)
        .getString("x_proxy", "").orEmpty()

    fun saveProxy(context: Context, value: String) {
        val clean = value.trim()
        require(clean.isEmpty() || runCatching {
            val uri = URI(clean)
            uri.scheme in setOf("http", "https", "socks5", "socks5h") &&
                !uri.host.isNullOrBlank() && uri.port in 1..65535 && uri.userInfo == null
        }.getOrDefault(false)) { "代理格式应为 http://主机:端口 或 socks5://主机:端口" }
        context.getSharedPreferences("network", 0).edit().putString("x_proxy", clean).apply()
    }

    fun apply(context: Context, request: YoutubeDLRequest, platform: Platform, api: String?,
              cookieDirectory: File = File(context.cacheDir, "x-requests")): File? {
        request.addOption("--socket-timeout", 20)
        request.addOption("--retries", 2)
        request.addOption("--extractor-retries", 1)
        if (platform == Platform.X) {
            request.addOption("--extractor-args", "twitter:api=${api ?: "graphql"}")
            proxy(context).takeIf { it.isNotBlank() }?.let { request.addOption("--proxy", it) }
            // 有 auth_token 时上游会强制 GraphQL；公开备用请求必须不附带登录 Cookie。
            if (api != "syndication") return XSession.attach(context, request, cookieDirectory)
        }
        return null
    }

    fun shouldRetryX(raw: String?): Boolean {
        val text = raw.orEmpty().lowercase()
        if (listOf("private", "login required", "not found", "http error 404", "removed",
                "name resolution", "certificate_verify_failed")
                .any { text.contains(it) }) return false
        // 主接口可能返回不完整的媒体信息，不能据此直接判定帖子没有视频。
        // syndication 会从主帖和 quoted_tweet 的 mediaDetails 中重新读取媒体。
        return listOf("http error 400", "http error 401", "http error 403", "http error 429",
            "graphql", "unable to extract", "unable to download", "timed out", "connection",
            "no video could be found", "no video formats found")
            .any { text.contains(it) }
    }
}
