package com.videodl.app.ytdlp

import android.content.Context
import android.webkit.CookieManager
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/** 持久会话不进入任务数据库或备份；每次 yt-dlp 调用使用独立快照。 */
object XSession {
    private fun file(context: Context) = File(context.noBackupFilesDir, "x-session/cookies.txt")
    private fun preferences(context: Context) = context.getSharedPreferences("x-session", 0)

    @Synchronized
    fun hasSavedSession(context: Context): Boolean = runCatching {
        XCookieFile.normalize(file(context).readText())
        true
    }.getOrDefault(false)

    @Synchronized
    fun save(context: Context, cookies: String, userAgent: String? = null) {
        val normalized = XCookieFile.normalize(cookies)
        val target = file(context)
        target.parentFile!!.mkdirs()
        val pending = File(target.parentFile, "cookies.tmp")
        pending.writeText(normalized)
        check(pending.renameTo(target)) { "无法保存 X 登录信息" }
        preferences(context).edit().putString("user_agent", userAgent?.takeIf {
            it.isNotBlank() && !it.contains('\n') && !it.contains('\r')
        }).apply()
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
        preferences(context).edit().clear().apply()
        // 只清理 X 会话，不调用 removeAllCookies，保留抖音游客信息。
        val manager = CookieManager.getInstance()
        for (domain in listOf("x.com", "twitter.com")) {
            for (name in listOf("auth_token", "ct0", "twid")) {
                for (scope in listOf("", "; Domain=.$domain")) {
                    manager.setCookie("https://$domain/", "$name=; Path=/; Max-Age=0; Secure$scope")
                }
            }
        }
        manager.flush()
    }

    @Synchronized
    fun attach(context: Context, request: YoutubeDLRequest, directory: File): File? {
        val source = file(context)
        if (!source.exists()) return null
        val normalized = runCatching { XCookieFile.normalize(source.readText()) }.getOrNull() ?: return null
        directory.mkdirs()
        val snapshot = File.createTempFile("x-session-", ".txt", directory)
        snapshot.writeText(normalized)
        request.addOption("--cookies", snapshot.absolutePath)
        preferences(context).getString("user_agent", null)?.let { request.addOption("--user-agent", it) }
        return snapshot
    }
}
