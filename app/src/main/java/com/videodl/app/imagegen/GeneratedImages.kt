package com.videodl.app.imagegen

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AtomicFile
import com.videodl.app.download.ImageFileType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class GeneratedImage(val id: String, val model: String, val prompt: String, val path: String, val created: Long)

/** 原图放在私有持久目录，预览不依赖有时效的 CDN 链接。 */
class GeneratedImages(context: Context) {
    private val folder = File(context.filesDir, "generated-images").apply { mkdirs() }
    private val index = AtomicFile(File(folder, "history.json"))

    fun load(): List<GeneratedImage> {
        if (!index.baseFile.exists()) return emptyList()
        val rows = JSONArray(index.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val file = File(folder, row.getString("file"))
            require(file.canonicalFile.parentFile == folder.canonicalFile) { "图片记录路径无效" }
            GeneratedImage(row.getString("id"), row.getString("model"), row.getString("prompt"),
                file.absolutePath, row.getLong("created"))
        }.filter { File(it.path).isFile }.sortedByDescending { it.created }
    }

    fun add(bytes: ByteArray, model: String, prompt: String, previous: List<GeneratedImage>): GeneratedImage {
        val extension = ImageFileType.extension(bytes) ?: error("无效图片")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "返回图片无法解码" }
        val id = UUID.randomUUID().toString()
        val file = File(folder, "$id.$extension")
        val item = GeneratedImage(id, model, prompt, file.absolutePath, System.currentTimeMillis())
        try {
            file.writeBytes(bytes)
            val rows = JSONArray()
            (listOf(item) + previous).forEach { image -> rows.put(JSONObject().put("id", image.id)
                .put("model", image.model).put("prompt", image.prompt).put("file", File(image.path).name)
                .put("created", image.created)) }
            val stream = index.startWrite()
            try {
                stream.write(rows.toString().toByteArray())
                index.finishWrite(stream)
            } catch (t: Throwable) {
                index.failWrite(stream)
                throw t
            }
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
        return item
    }
}
