package com.videodl.app.ytdlp

/** 带原帖图片总数的选择快照。旧任务和未选择的任务均不隐式全选。 */
data class GallerySelection(val total: Int, val indices: List<Int>) {
    fun encode(): String = "$total|${indices.joinToString(",")}"
    val label: String get() = "已选 ${indices.size}/$total 张图片"
    fun matches(count: Int): Boolean = total == count

    companion object {
        fun create(total: Int, indices: Collection<Int>): GallerySelection? {
            if (total <= 0 || indices.any { it !in 0 until total }) return null
            return GallerySelection(total, indices.distinct().sorted())
        }
        fun decode(raw: String?): GallerySelection? {
            val parts = raw?.split('|') ?: return null
            if (parts.size != 2) return null
            val total = parts[0].toIntOrNull() ?: return null
            val indices = if (parts[1].isEmpty()) emptyList() else parts[1].split(',').map {
                it.toIntOrNull() ?: return null
            }
            return create(total, indices)
        }
    }
}
