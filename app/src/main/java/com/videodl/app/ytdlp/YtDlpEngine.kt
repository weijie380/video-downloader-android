package com.videodl.app.ytdlp

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.io.File
import java.util.zip.ZipFile
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class ProbeResult(
    val title: String,
    val uploader: String?,
    val durationSeconds: Int,
    val thumbnailUrl: String?,
    val formats: List<FormatOption>,
    val resolvedUrl: String,
    val note: String? = null,
    val resolver: String? = null,
    val downloadInfoJson: String? = null,
)

/** 解析失败时携带可读原因，界面直接展示，不吞掉平台侧的报错。 */
class ProbeFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * yt-dlp 引擎封装。所有调用最终落到 youtubedl-android 提供的内嵌 Python + yt-dlp。
 */
object YtDlpEngine {

    @Volatile
    private var initialized = false
    private val initLock = Any()
    private val engineLock = ReentrantReadWriteLock()

    fun <T> withEngine(block: () -> T): T = engineLock.read(block)

    /** 首次调用会解压内嵌 Python 与 yt-dlp，耗时较长，只做一次。 */
    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            val app = context.applicationContext
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                installBundledEngine(app)
            } catch (e: YoutubeDLException) {
                throw ProbeFailure("解析引擎初始化失败：${readable(e.message)}", e)
            }
            initialized = true
        }
    }

    fun versionLabel(context: Context): String {
        ensureInitialized(context)
        return installedVersion(context) ?: runCatching { YoutubeDL.getInstance().version(context) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "未知"
    }

    private fun engineFile(context: Context) = File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")

    private fun installedVersion(context: Context): String? = runCatching {
        ZipFile(engineFile(context)).use { zip ->
            val entry = zip.getEntry("yt_dlp/version.py") ?: return@use null
            val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            Regex("__version__ = '([0-9.]+)'").find(text)?.groupValues?.get(1)
        }
    }.getOrNull()

    /** 覆盖安装也升级旧引擎；用户在线更新到更高版本后不降级。 */
    private fun installBundledEngine(context: Context) {
        if ((installedVersion(context) ?: "") >= "2026.08.19") return
        val target = engineFile(context)
        val pending = File(target.parentFile, "bundled.tmp")
        context.resources.openRawResource(com.videodl.app.R.raw.ytdlp).use { source ->
            pending.outputStream().use { source.copyTo(it) }
        }
        check(pending.renameTo(target)) { "内置解析引擎升级失败" }
    }

    /** 从 GitHub 拉取最新 yt-dlp；平台改版导致的解析失败可先靠这一步解决。 */
    fun updateEngine(context: Context): Result<String> = runCatching { engineLock.write {
        ensureInitialized(context)
        YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
        versionLabel(context)
    } }

    suspend fun probe(
        context: Context,
        url: String,
        platform: Platform,
        pageIndex: Int?,
    ): ProbeResult = withContext(Dispatchers.IO) {
        if (platform == Platform.DOUYIN) {
            try { DouyinGuestParser.probe(url) }
            catch (e: ProbeFailure) { throw e }
            catch (e: Exception) { throw ProbeFailure("抖音解析失败：${readable(e.message)}", e) }
        } else withEngine { probeBlocking(context, url, platform, pageIndex) }
    }

    private fun probeBlocking(
        context: Context,
        url: String,
        platform: Platform,
        pageIndex: Int?,
    ): ProbeResult {
        ensureInitialized(context)

        // B 站分 P：URL 上的 ?p=N 会让 extractor 取不到 cid（400），必须去掉，
        // 只靠 --playlist-items 选取条目；否则 --dump-json 还会输出多行 JSON 导致解析失败。
        val target = if (platform == Platform.BILIBILI) {
            UrlUtils.withoutBilibiliPage(url)
        } else if (platform == Platform.X) {
            UrlUtils.normalizeX(url)
        } else {
            url
        }

        var api: String? = if (platform == Platform.X) "graphql" else null
        val hasSession = platform == Platform.X && XSession.hasSavedSession(context)
        fun sessionHint() = if (hasSession)
            "\n已使用保存的 X 登录信息；登录可能已过期或此账号无权访问，请在「X 登录」中重新登录后重试。"
        else "\n当前使用访客身份。需要登录才能观看的视频，请先点「X 登录」保存登录信息，再重试。"
        fun fetch(): com.yausername.youtubedl_android.mapper.VideoInfo {
            val request = YoutubeDLRequest(target).apply {
                addOption("--no-warnings")
                if (platform == Platform.BILIBILI) addOption("--playlist-items", pageIndex ?: 1)
                else addOption("--no-playlist")
            }
            val cookies = RequestPolicy.apply(context, request, platform, api)
            return try { YoutubeDL.getInstance().getInfo(request) } finally { cookies?.delete() }
        }
        val info = try {
            try { fetch() } catch (e: YoutubeDLException) {
                if (platform != Platform.X ||
                    !RequestPolicy.shouldRetryX(e.message)) throw e
                val first = readable(e.message)
                api = "syndication"
                try { fetch() } catch (second: YoutubeDLException) {
                    throw ProbeFailure("X 的两个接口均未解析成功。\n${if (hasSession) "登录" else "访客主"}接口：$first\n公开备用接口：${readable(second.message)}" +
                        if (second.message.orEmpty().contains("no video", true))
                            sessionHint() + "\n引用帖请打开原视频所属帖子后复制链接。" else "", second)
                }
            }
        } catch (e: YoutubeDL.CanceledException) {
            throw ProbeFailure("解析已取消", e)
        } catch (e: YoutubeDLException) {
            throw ProbeFailure("解析失败：${readable(e.message)}" +
                if (platform == Platform.X && listOf("login", "not authorized", "no video", "unauthorized")
                        .any { e.message.orEmpty().contains(it, true) }) sessionHint() else "", e)
        } catch (e: InterruptedException) {
            throw ProbeFailure("解析被中断", e)
        }

        val title = info.title?.takeIf { it.isNotBlank() }
            ?: info.fulltitle?.takeIf { it.isNotBlank() }
            ?: "未命名视频"

        val rawFormats = buildList {
            info.formats?.let { addAll(it) }
            if (isEmpty()) info.requestedFormats?.let { addAll(it) }
        }

        val options = FormatBuilder.build(rawFormats)
        if (options.isEmpty()) throw ProbeFailure("该链接没有返回可下载的视频格式")
        val note = when {
            rawFormats.isEmpty() -> "该链接没有返回任何可下载格式"
            options.size == 1 && options.first().height == 0 ->
                "未能获取分辨率分档，将按 yt-dlp 的最优组合下载"
            else -> null
        }

        return ProbeResult(
            title = title,
            uploader = info.uploader?.takeIf { it.isNotBlank() },
            durationSeconds = info.duration.coerceAtLeast(0),
            thumbnailUrl = info.thumbnail?.takeIf { it.isNotBlank() },
            formats = options,
            // X 的网页元数据地址可能丢掉 /video/N，下载必须保留用户指定的视频序号。
            resolvedUrl = if (platform == Platform.X) target
                else info.webpageUrl?.takeIf { it.isNotBlank() } ?: url,
            note = note,
            resolver = api,
        )
    }

    /**
     * yt-dlp 的报错以 stderr 整段返回，这里提取最后一条 ERROR 行，
     * 让用户看到「平台改版 / 需要登录 / 链接失效」之类的具体原因。
     */
    fun readable(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "未知错误"
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val errorLine = lines.lastOrNull { it.contains("ERROR", ignoreCase = true) }
            ?: lines.lastOrNull()
            ?: return "未知错误"
        val core = errorLine
            .replace(Regex("^ERROR:\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^\\[\\w+\\]\\s*"), "")
            .replace(Regex("(https?://[^\\s?]+)\\?[^\\s]+"), "$1?…")
            .trim()
            .take(2000)
            .ifBlank { "未知错误" }
        val context = lines.filter { it != errorLine &&
            listOf("HTTP Error", "Unable to download fragment", "timed out", "Connection reset")
                .any { cause -> it.contains(cause, true) } }
            .distinct().takeLast(3).joinToString("\n") { line ->
                // 错误详情只保留主机和路径，不显示媒体地址里的签名或认证参数。
                line.replace(Regex("(https?://[^\\s?]+)\\?[^\\s]+"), "$1?…").take(300)
            }
        return friendly(core) + if (context.isNotBlank()) "\n请求详情：\n$context" else ""
    }

    /**
     * 把几个平台特有的报错翻成用户能直接照做的说明；
     * 没有对应说明时原样返回 yt-dlp 的原因，绝不吞掉。
     */
    private fun friendly(message: String): String {
        val lower = message.lowercase(Locale.ROOT)
        val hint = when {
            lower.contains("downloaded file is empty") || lower.contains("did not get any data blocks") ->
                "未收到有效的视频数据。可能是媒体链接已失效或分片连接失败；请检查 VPN／代理连接后重试。"

            lower.contains("fragment not found") || lower.contains("unable to download fragment") ->
                "视频分片下载失败，已停止保存以免生成残缺文件。请检查 VPN／代理连接后重试。"

            lower.contains("fresh cookies") ->
                "抖音需要新的游客 Cookie，请在官方分享页完成游客验证后重试，无需注册账号。"

            lower.contains("only available for registered users") ->
                "这个视频只对登录用户开放，需要 cookies 才能下载。"

            lower.contains("premium member") ->
                "该画质需要大会员权限，请在画质列表里选较低的公开画质。"

            // 平台明确返回的状态码优先于通用网络判断，避免把 404 误报成「网络问题」
            lower.contains("http error 404") || lower.contains("404: not found") ->
                "链接已失效（404），视频可能已被删除。"

            lower.contains("http error 403") ->
                "平台拒绝了本次请求（403），该视频可能有访问限制，可稍后重试。"

            lower.contains("unable to download video info") || lower.contains("400:") ->
                "平台未能返回视频信息，请核对链接或查看下方的接口错误详情。"

            lower.contains("no video could be found in this tweet") ->
                "X 当前接口没有返回可下载视频信息。"

            lower.contains("no video could be found") || lower.contains("no video formats found") ->
                "这条链接里没有找到可下载的视频。"

            lower.contains("video is unavailable") || lower.contains("has been removed") ->
                "视频不可用，可能已被删除或设为私密。"

            lower.contains("private") || lower.contains("login required") ->
                "视频是私密的，需要登录才能访问。"

            lower.contains("unsupported url") ->
                "yt-dlp 不支持这个链接，请确认它指向单个公开视频。"

            lower.contains("name resolution") || lower.contains("unable to resolve host") ||
                lower.contains("nodename nor servname") ->
                "DNS 解析失败：无法找到平台服务器。请检查手机网络或 X 代理设置。"

            lower.contains("certificate") || lower.contains("ssl") || lower.contains("tls") ->
                "安全连接失败（TLS），请检查手机时间和代理配置。"

            lower.contains("timed out") || lower.contains("timeout") ->
                "连接平台超时。请确认手机浏览器能访问该平台，再检查代理设置。"

            lower.contains("connection refused") ->
                "连接被拒绝，请检查平台连接或代理地址及端口。"

            // 通用网络兜底放在最后：带明确状态码的情况已经在上面拦下了
            lower.contains("timed out") || lower.contains("connection") ||
                lower.contains("network is unreachable") || lower.contains("unable to download webpage") ->
                "网络请求失败，请检查网络后重试。"

            else -> null
        }
        return if (hint == null) message else "$hint\n（yt-dlp：$message）"
    }
}
