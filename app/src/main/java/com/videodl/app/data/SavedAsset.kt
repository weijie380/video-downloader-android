package com.videodl.app.data

import org.json.JSONArray
import org.json.JSONObject

data class SavedAsset(val uri: String, val name: String, val mime: String, val bytes: Long) {
    companion object {
        fun toJson(assets: List<SavedAsset>): String = JSONArray().apply {
            assets.forEach { put(JSONObject().put("uri", it.uri).put("name", it.name)
                .put("mime", it.mime).put("bytes", it.bytes)) }
        }.toString()

        fun fromJson(raw: String?): List<SavedAsset> = runCatching {
            val array = JSONArray(raw ?: "[]")
            (0 until array.length()).map { index ->
                val entry = array.getJSONObject(index)
                SavedAsset(entry.getString("uri"), entry.getString("name"), entry.getString("mime"), entry.getLong("bytes"))
            }
        }.getOrDefault(emptyList())
    }
}
