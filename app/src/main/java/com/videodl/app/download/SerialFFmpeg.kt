package com.videodl.app.download

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/** 给独立 yt-dlp 进程安装共用的 FFmpeg 后处理锁；下载本身不占用这个锁。 */
object SerialFFmpeg {
    private fun root(context: Context) = File(context.noBackupFilesDir, "download-plugins")

    fun install(context: Context) {
        val plugin = File(root(context), "serial/yt_dlp_plugins/postprocessor/serial_ffmpeg.py")
        plugin.parentFile!!.mkdirs()
        // Service 建立时写入，此后只读，两个下载进程不会互相覆盖插件。
        context.assets.open("serial_ffmpeg.py").use { input ->
            plugin.outputStream().use { input.copyTo(it) }
        }
    }

    fun configure(context: Context, request: YoutubeDLRequest) {
        request.addOption("--plugin-dirs", root(context).absolutePath)
        request.addOption("--use-postprocessor", "SerialFFmpeg:when=pre_process;lock_path=${File(root(context), "ffmpeg.lock").absolutePath}")
    }
}
