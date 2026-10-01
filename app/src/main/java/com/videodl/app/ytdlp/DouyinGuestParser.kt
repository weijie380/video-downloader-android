package com.videodl.app.ytdlp

import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** 公开分享页的游客会话。与 App 内的官方验证页共用 Cookie，不要求账号。 */
object DouyinGuestParser {
    const val USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) " +
        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"
    private val cookieManager get() = CookieManager.getInstance()

    fun videoId(url: String): String? {
        Regex("/(?:video|note|slides|share/video)/(\\d+)").find(url)?.let { return it.groupValues[1] }
        val query = runCatching { URI(url).rawQuery }.getOrNull().orEmpty()
        return query.split('&').map { it.split('=', limit = 2) }.firstOrNull {
            it.size == 2 && it[0] in setOf("modal_id", "aweme_id", "item_ids", "group_id") &&
                it[1].all(Char::isDigit) && it[1].isNotEmpty()
        }?.get(1)
    }

    fun shareUrl(url: String): String {
        val resolved = if (videoId(url) != null) url else request(url).first
        val id = videoId(resolved)
            ?: throw ProbeFailure("没有找到抖音作品编号，请复制单个视频或图文的分享链接")
        // 新版图文短链跳到 /share/slides/；该页面是客户端模板，
        // 使用同作品的官方 note 分享页读取游客媒体元数据。
        val path = URI(resolved).path.orEmpty()
        val kind = if (path.contains("/note/") || path.contains("/slides/")) "note" else "video"
        return "https://www.iesdouyin.com/share/$kind/$id/"
    }

    fun probe(url: String): ProbeResult {
        bootstrapGuestCookie()
        val share = shareUrl(url)
        val id = videoId(share)!!
        var lastCookies: String? = null
        for (attempt in 0 until 3) {
            val html = request(share).second
            itemFromRouter(SharePageJson.extract(html))?.let { return fromItem(it, share, id) }
            val cookies = cookieManager.getCookie(share).orEmpty()
            if (attempt > 0 && cookies == lastCookies) break
            lastCookies = cookies
        }
        // 官方分享页使用的接口；不使用外部解析服务，也不把 Cookie 发给第三方。
        val body = request("https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=$id&use_new_select_scope=0").second
        val response = runCatching { JSONObject(body) }.getOrNull()
        response?.optJSONArray("item_list")?.optJSONObject(0)?.let { return fromItem(it, share, id) }
        throw ProbeFailure("抖音暂未返回公开视频信息。请点「游客验证」，在官方分享页完成验证后重试；无需注册账号。" +
            (response?.optInt("status_code")?.takeIf { it != 0 }?.let { "\n平台状态码：$it" } ?: ""))
    }

    /** 字节跳动官方游客标识接口。返回值没有账号登录权限。 */
    private fun bootstrapGuestCookie() {
        if (cookieManager.getCookie("https://www.iesdouyin.com/")
                ?.split(';')?.any { it.trim().startsWith("ttwid=") } == true) return
        val connection = URL("https://ttwid.bytedance.com/ttwid/union/register/")
            .openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
            val payload = JSONObject().put("region", "cn").put("aid", 1768)
                .put("needFid", false).put("service", "www.iesdouyin.com")
                .put("migrate_info", JSONObject().put("ticket", "").put("source", "node"))
                .put("cbUrlProtocol", "https").put("union", true).toString()
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return
            val cookies = connection.headerFields.filterKeys { it?.equals("Set-Cookie", true) == true }
                .values.flatten()
            val token = cookies.firstNotNullOfOrNull { Regex("(?:^|[;,]\\s*)ttwid=([^;]+)").find(it)?.groupValues?.get(1) }
                ?: return
            cookieManager.setCookie("https://www.iesdouyin.com/", "ttwid=$token; Domain=.iesdouyin.com; Path=/; Secure; HttpOnly")
            cookieManager.setCookie("https://www.douyin.com/", "ttwid=$token; Domain=.douyin.com; Path=/; Secure; HttpOnly")
            cookieManager.flush()
        } catch (_: Exception) {
            // 官方注册接口不可达时仍尝试分享页，必要时由 WebView 建立游客会话。
        } finally { connection.disconnect() }
    }

    fun fromRouter(raw: String, share: String): ProbeResult? {
        val id = videoId(share) ?: return null
        return itemFromRouter(raw)?.let { fromItem(it, share, id) }
    }

    /** Cookie 只在发起下载时按目标域名注入，不存入任务数据库。 */
    fun downloadInfo(raw: String): String {
        val info = JSONObject(raw)
        val formats = info.getJSONArray("formats")
        for (i in 0 until formats.length()) {
            val format = formats.getJSONObject(i)
            val cleanUrl = DouyinVideoSource.withoutWatermark(format.getString("url"))
                ?: throw ProbeFailure("没有可用的无水印播放地址，请重新解析")
            format.put("url", cleanUrl)
            val headers = format.optJSONObject("http_headers") ?: JSONObject()
            cookieManager.getCookie(format.getString("url"))?.takeIf { it.isNotBlank() }
                ?.let { headers.put("Cookie", it) }
            format.put("http_headers", headers)
        }
        return info.toString()
    }

    private fun itemFromRouter(raw: String?): JSONObject? {
        val loader = raw?.let { runCatching { JSONObject(it).optJSONObject("loaderData") }.getOrNull() }
            ?: return null
        for (key in loader.keys()) {
            val item = loader.optJSONObject(key)?.optJSONObject("videoInfoRes")
                ?.optJSONArray("item_list")?.optJSONObject(0)
            if (item != null) return item
        }
        return null
    }

    private fun fromItem(item: JSONObject, share: String, id: String): ProbeResult {
        DouyinGallery.fromItem(item, id)?.let { return it.toProbe() }
        val video = item.optJSONObject("video") ?: throw ProbeFailure("该分享内容不是普通视频")
        val formats = JSONArray()
        val options = mutableListOf<FormatOption>()
        fun add(address: JSONObject?, height: Int, width: Int, label: String) {
            val urls = address?.optJSONArray("url_list") ?: return
            val mediaUrl = (0 until urls.length()).mapNotNull {
                DouyinVideoSource.withoutWatermark(urls.optString(it))
            }.firstOrNull() ?: return
            val formatId = "share${formats.length()}"
            val headers = JSONObject().put("User-Agent", USER_AGENT).put("Referer", share)
            formats.put(JSONObject().put("format_id", formatId).put("url", mediaUrl).put("ext", "mp4")
                .put("height", height).put("width", width).put("http_headers", headers))
            val qualityHeight = if (width > 0 && height > width) width else height
            val quality = if (qualityHeight > 0) "${qualityHeight}p" +
                (if (height > width && width > 0) "（竖屏）" else "") else label
            options += FormatOption(formatId, quality,
                if (qualityHeight > 0) "无水印播放源 · MP4" else "无水印播放源 · 分辨率未确认",
                formatId, qualityHeight,
                address.optLong("data_size", 0), false)
        }
        video.optJSONArray("bit_rate")?.let { rates ->
            for (i in 0 until rates.length()) {
                val rate = rates.optJSONObject(i) ?: continue
                val addr = rate.optJSONObject("play_addr")
                val h = addr?.optInt("height") ?: 0
                add(addr, h, addr?.optInt("width") ?: 0,
                    if (h > 0) "${h}p" else "分享页画质")
            }
        }
        if (options.isEmpty()) {
            // video.width/height 是原视频尺寸，分享播放地址可能实际只提供 720p。
            // 不把原视频尺寸当作当前下载流的分辨率。
            val addr = video.optJSONObject("play_addr")
            val h = addr?.optInt("height") ?: 0
            add(addr, h, addr?.optInt("width") ?: 0, if (h > 0) "${h}p" else "分享页画质")
        }
        if (options.isEmpty()) throw ProbeFailure("抖音返回的信息中没有可用的无水印视频地址")
        val title = item.optString("desc").ifBlank { "抖音视频 $id" }
        val duration = (video.optLong("duration") / 1000).toInt().coerceAtLeast(0)
        val info = JSONObject().put("id", id).put("title", title).put("webpage_url", share)
            .put("extractor", "DouyinShare").put("extractor_key", "Generic").put("duration", duration)
            .put("formats", formats)
        return ProbeResult(title, item.optJSONObject("author")?.optString("nickname"), duration,
            video.optJSONObject("cover")?.optJSONArray("url_list")?.optString(0),
            options.sortedByDescending { it.height }.distinctBy { it.height }, share,
            note = if (options.all { it.height == 0 }) "分享页提供的画质可能低于原视频，未确认分辨率。" else null,
            resolver = "douyin_share", downloadInfoJson = info.toString())
    }

    private fun request(initialUrl: String): Pair<String, String> {
        var current = initialUrl
        repeat(8) {
            if (UrlUtils.platformOf(current) != Platform.DOUYIN || URI(current).scheme != "https") {
                throw ProbeFailure("抖音分享链接重定向到了非官方地址")
            }
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 20_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Referer", "https://www.douyin.com/")
                connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                cookieManager.getCookie(current)?.let { connection.setRequestProperty("Cookie", it) }
                val code = connection.responseCode
                connection.headerFields.filterKeys { it?.equals("Set-Cookie", true) == true }
                    .values.flatten().forEach { cookieManager.setCookie(current, it) }
                cookieManager.flush()
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: throw ProbeFailure("抖音跳转地址为空")
                    current = URI(current).resolve(location).toString()
                } else {
                    if (code !in 200..299) throw ProbeFailure("抖音分享页请求失败：HTTP $code")
                    val body = connection.inputStream.bufferedReader().use { reader ->
                        val buffer = CharArray(8192)
                        val text = StringBuilder()
                        while (true) {
                            val read = reader.read(buffer)
                            if (read < 0) break
                            text.append(buffer, 0, read)
                            if (text.length > 4_000_000) throw ProbeFailure("分享页响应过大，请重试")
                        }
                        text.toString()
                    }
                    return current to body
                }
            } finally { connection.disconnect() }
        }
        throw ProbeFailure("抖音分享链接跳转次数过多")
    }
}
