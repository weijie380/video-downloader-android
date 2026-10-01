package com.videodl.app.download

/** 按实际文件内容决定后缀，避免把 WebP 或错误网页伪装为 JPEG。 */
object ImageFileType {
    fun extension(bytes: ByteArray): String? {
        fun at(index: Int, value: Int) = bytes.size > index && (bytes[index].toInt() and 255) == value
        fun text(start: Int, value: String) = bytes.size >= start + value.length &&
            String(bytes, start, value.length, Charsets.US_ASCII) == value
        return when {
            at(0, 255) && at(1, 216) && at(2, 255) -> "jpg"
            bytes.size >= 8 && bytes.take(8).map { it.toInt() and 255 } ==
                listOf(137, 80, 78, 71, 13, 10, 26, 10) -> "png"
            text(0, "RIFF") && text(8, "WEBP") -> "webp"
            text(0, "GIF87a") || text(0, "GIF89a") -> "gif"
            text(4, "ftyp") && (text(8, "avif") || text(8, "avis")) -> "avif"
            else -> null
        }
    }
}
