package com.videodl.app.download

import android.graphics.BitmapFactory
import android.webkit.CookieManager
import com.videodl.app.ytdlp.DouyinGuestParser
import com.videodl.app.ytdlp.GalleryImage
import kotlinx.coroutines.CancellationException
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** 逐张流式保存；取消会断开当前图片连接，不启动 yt-dlp/FFmpeg。 */
class DouyinImageDownloader {
    @Volatile private var canceled = false
    @Volatile private var active: HttpURLConnection? = null

    fun cancel() { canceled = true; active?.disconnect() }
    fun checkCanceled() { if (canceled) throw CancellationException("图文下载已取消") }

    fun download(image: GalleryImage, folder: File, index: Int, referer: String): File {
        val pending = File(folder, "image_$index.part")
        var lastError: Exception? = null
        for (url in image.urls) {
            checkCanceled()
            try {
                downloadUrl(url, pending, referer)
                val header = pending.inputStream().use { input ->
                    val bytes = ByteArray(32)
                    val count = input.read(bytes)
                    bytes.copyOf(count.coerceAtLeast(0))
                }
                val extension = ImageFileType.extension(header) ?: error("服务器没有返回有效图片")
                if (extension != "avif") {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(pending.absolutePath, bounds)
                    check(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片文件损坏或不完整" }
                }
                val result = File(folder, "${(index + 1).toString().padStart(3, '0')}.$extension")
                check(pending.renameTo(result)) { "无法保存临时图片" }
                return result
            } catch (e: Exception) {
                checkCanceled()
                pending.delete()
                lastError = e
            }
        }
        throw IllegalStateException("第 ${index + 1} 张图片下载失败：${lastError?.message ?: "地址不可用"}。请重试刷新图片地址。", lastError)
    }

    private fun downloadUrl(initial: String, target: File, referer: String) {
        var url = initial
        repeat(8) {
            checkCanceled()
            val uri = URI(url)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "图片地址格式无效" }
            val connection = URL(url).openConnection() as HttpURLConnection
            active = connection
            try {
                checkCanceled()
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("User-Agent", DouyinGuestParser.USER_AGENT)
                connection.setRequestProperty("Referer", referer)
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
                    ?.let { connection.setRequestProperty("Cookie", it) }
                val code = connection.responseCode
                if (code in 300..399) {
                    url = uri.resolve(connection.getHeaderField("Location") ?: error("图片跳转地址为空")).toString()
                } else {
                    check(code in 200..299) { "HTTP $code" }
                    val expected = connection.contentLengthLong
                    var count = 0L
                    connection.inputStream.use { input -> target.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            checkCanceled()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            count += read
                            check(count <= 100_000_000) { "图片响应过大" }
                        }
                    } }
                    checkCanceled()
                    check(count > 0 && (expected <= 0 || count == expected)) { "图片文件不完整" }
                    return
                }
            } finally { active = null; connection.disconnect() }
        }
        error("图片跳转次数过多")
    }
}
