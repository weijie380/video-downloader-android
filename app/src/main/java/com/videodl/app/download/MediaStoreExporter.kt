package com.videodl.app.download

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.Locale

/**
 * 把临时目录里的成品写入系统「下载」目录。
 *
 * 使用 MediaStore（API 29+ 的 Downloads 集合），不需要任何存储权限，
 * 文件在系统文件管理器里可见。
 */
object MediaStoreExporter {

    /** 下载目录下的子目录，便于用户集中找到本 App 的产物。 */
    const val SUB_DIR = "视频下载器"

    private val ILLEGAL = Regex("""[\\/:*?"<>|\r\n\t]""")

    data class Exported(val uri: Uri, val displayName: String)

    private val relativePath: String
        get() = "${Environment.DIRECTORY_DOWNLOADS}/$SUB_DIR/"

    fun sanitize(name: String): String {
        val cleaned = ILLEGAL.replace(name, "_").trim().trim('.')
        val limited = if (cleaned.length > 120) cleaned.take(120) else cleaned
        return limited.ifBlank { "video_${System.currentTimeMillis()}" }
    }

    fun export(
        context: Context,
        source: File,
        displayName: String,
        mimeType: String,
        subFolder: String? = null,
        pending: Boolean = false,
        replaceExisting: Boolean = true,
    ): Exported {
        require(source.isFile && source.length() > 0) { "临时文件不存在或为空" }

        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val safeName = sanitize(displayName)
        val path = relativePath + subFolder?.let { sanitize(it) + "/" }.orEmpty()

        // 同名旧文件先删掉，避免重复下载时系统自动改名成 "xxx (1).mp4"
        if (replaceExisting) runCatching {
            resolver.delete(
                collection,
                "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf(safeName, path),
            )
        }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safeName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, path)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values)
            ?: error("无法在系统下载目录创建文件（可能是存储空间不足）")

        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE * 8) }
            } ?: error("无法写入系统下载目录")
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }

        if (!pending) {
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }

        return Exported(uri, safeName)
    }

    fun publish(context: Context, files: List<Exported>) {
        val updates = ArrayList(files.map { file ->
            android.content.ContentProviderOperation.newUpdate(file.uri)
                .withValue(MediaStore.Downloads.IS_PENDING, 0).build()
        })
        context.contentResolver.applyBatch(MediaStore.AUTHORITY, updates)
    }

    fun remove(context: Context, files: List<Exported>) {
        files.forEach { runCatching { context.contentResolver.delete(it.uri, null, null) } }
    }

    /** 判断某个 URI 对应的下载记录是否还在（用户可能在文件管理器里删掉了）。 */
    fun stillExists(context: Context, uri: Uri): Boolean = runCatching {
        val id = runCatching { ContentUris.parseId(uri) }.getOrDefault(-1L)
        if (id < 0) return false
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads._ID} = ?",
            arrayOf(id.toString()),
            null,
        )?.use { it.moveToFirst() } ?: false
    }.getOrDefault(false)

    fun mimeTypeFor(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "flv" -> "video/x-flv"
        "avi" -> "video/x-msvideo"
        "ts" -> "video/mp2t"
        "3gp" -> "video/3gpp"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "avif" -> "image/avif"
        "txt" -> "text/plain"
        else -> "video/*"
    }
}
