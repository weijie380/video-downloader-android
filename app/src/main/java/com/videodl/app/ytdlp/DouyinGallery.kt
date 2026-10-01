package com.videodl.app.ytdlp

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class GalleryImage(val urls: List<String>, val width: Int, val height: Int)

data class DouyinGallery(
    val id: String,
    val caption: String,
    val author: String,
    val shareUrl: String,
    val images: List<GalleryImage>,
) {
    fun toJson(): String = JSONObject().put("id", id).put("caption", caption)
        .put("author", author).put("share_url", shareUrl).put("images", JSONArray().apply {
            images.forEach { image -> put(JSONObject().put("urls", JSONArray(image.urls))
                .put("width", image.width).put("height", image.height)) }
        }).toString()

    fun toProbe(): ProbeResult = ProbeResult(
        title = caption.ifBlank { "抖音图文 $id" }, uploader = author.takeIf { it.isNotBlank() },
        durationSeconds = 0, thumbnailUrl = images.first().urls.first(),
        formats = listOf(FormatOption("gallery", "请选择图片（共 ${images.size} 张）", "勾选图片后按原帖顺序保存及文案",
            "gallery", 0, 0, false)),
        resolvedUrl = shareUrl, resolver = "douyin_gallery", downloadInfoJson = toJson(),
    )

    companion object {
        private fun urls(array: JSONArray?): List<String> = if (array == null) emptyList() else
            (0 until array.length()).map { array.optString(it) }.filter { url ->
                runCatching { val uri = URI(url); uri.scheme == "https" && !uri.host.isNullOrBlank() }
                    .getOrDefault(false)
            }.distinct()

        fun fromItem(item: JSONObject, id: String): DouyinGallery? {
            val post = item.optJSONObject("image_post_info")
            val array = listOf(item.optJSONArray("images"), post?.optJSONArray("images"),
                post?.optJSONArray("image_list"), item.optJSONArray("image_list"))
                .firstOrNull { it != null && it.length() > 0 } ?: return null
            val images = (0 until array.length()).map { index ->
                val image = array.optJSONObject(index) ?: throw ProbeFailure("第 ${index + 1} 张图片信息不完整，请重试解析")
                val candidates = buildList {
                    addAll(urls(image.optJSONObject("download_url")?.optJSONArray("url_list")))
                    addAll(urls(image.optJSONObject("download_addr")?.optJSONArray("url_list")))
                    addAll(urls(image.optJSONArray("download_url_list")))
                    addAll(urls(image.optJSONArray("url_list")))
                    addAll(urls(image.optJSONObject("display_image")?.optJSONArray("url_list")))
                }.distinct()
                if (candidates.isEmpty()) throw ProbeFailure("第 ${index + 1} 张图片缺少下载地址，请重新解析或完成游客验证")
                GalleryImage(candidates, image.optInt("width"), image.optInt("height"))
            }
            return DouyinGallery(id, item.optString("desc"),
                item.optJSONObject("author")?.optString("nickname").orEmpty(),
                "https://www.iesdouyin.com/share/note/$id/", images)
        }

        fun fromJson(raw: String): DouyinGallery {
            val info = JSONObject(raw)
            val array = info.getJSONArray("images")
            val images = (0 until array.length()).map { index ->
                val image = array.getJSONObject(index)
                val candidates = urls(image.optJSONArray("urls"))
                require(candidates.isNotEmpty()) { "图片地址已失效，请重新解析" }
                GalleryImage(candidates, image.optInt("width"), image.optInt("height"))
            }
            require(images.isNotEmpty()) { "图文没有返回图片，请重新解析" }
            return DouyinGallery(info.getString("id"), info.optString("caption"),
                info.optString("author"), info.getString("share_url"), images)
        }
    }
}
