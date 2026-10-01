package com.videodl.app.ytdlp

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条可选的画质。
 *
 * [selector] 是交给 yt-dlp 的 `-f` 表达式；[approxBytes] 是预估体积（0 表示未知）；
 * [containerNote] 在源格式无法无损封装为 MP4 时给出界面提示。
 */
data class FormatOption(
    val id: String,
    val qualityLabel: String,
    val detail: String,
    val selector: String,
    val height: Int,
    val approxBytes: Long,
    val needsMerge: Boolean,
    val containerNote: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("qualityLabel", qualityLabel)
        put("detail", detail)
        put("selector", selector)
        put("height", height)
        put("approxBytes", approxBytes)
        put("needsMerge", needsMerge)
        put("containerNote", containerNote ?: JSONObject.NULL)
    }

    companion object {
        fun listToJson(list: List<FormatOption>): String {
            val array = JSONArray()
            list.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        fun listFromJson(raw: String?): List<FormatOption> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).map { index ->
                    val o = array.getJSONObject(index)
                    FormatOption(
                        id = o.optString("id"),
                        qualityLabel = o.optString("qualityLabel"),
                        detail = o.optString("detail"),
                        selector = o.optString("selector"),
                        height = o.optInt("height"),
                        approxBytes = o.optLong("approxBytes"),
                        needsMerge = o.optBoolean("needsMerge"),
                        containerNote = o.optString("containerNote").takeIf {
                            o.has("containerNote") && !o.isNull("containerNote") && it.isNotBlank()
                        },
                    )
                }
            }.getOrDefault(emptyList())
        }
    }
}