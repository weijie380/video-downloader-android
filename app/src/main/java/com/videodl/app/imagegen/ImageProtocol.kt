package com.videodl.app.imagegen

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.Base64

data class ImageModel(val id: String, val name: String, val price: String? = null, val available: Boolean = true)
data class ImageSource(val url: String? = null, val bytes: ByteArray? = null)

object ImageProtocol {
    val models = listOf(ImageModel("qwen-image-2.0", "Qwen Image 2.0"), ImageModel("wan2.7-image", "Wan 2.7 Image"))
    const val MAX_IMAGE_BYTES = 32 * 1024 * 1024

    fun request(model: String, prompt: String): JSONObject {
        require(models.any { it.id == model }) { "请选择支持的生图模型" }
        require(prompt.isNotBlank() && prompt.length <= 4000) { "请输入 1～4000 字的图片描述" }
        return JSONObject().put("model", model).put("prompt", prompt.trim()).put("n", 1)
    }

    fun parseModels(json: String): List<ImageModel> {
        val rows = JSONObject(json).getJSONArray("data")
        return models.map { known ->
            val item = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.firstOrNull { it.optString("id") == known.id }
            known.copy(price = item?.optString("price")?.takeIf { it.isNotBlank() },
                available = item != null && item.optBoolean("effectiveAvailable", true) && item.optString("status") == "online")
        }
    }

    fun parseImage(json: String): ImageSource {
        val root = JSONObject(json)
        val row = root.optJSONArray("data")?.optJSONObject(0) ?: root.optJSONObject("data")?.optJSONArray("data")?.optJSONObject(0)
            ?: error("服务没有返回图片，请核对生图接口或稍后重试")
        val base64 = row.optString("b64_json").takeIf { it.isNotBlank() }
        if (base64 != null) {
            require(base64.length <= MAX_IMAGE_BYTES * 4 / 3 + 8) { "返回图片过大" }
            val decoded = runCatching { Base64.getDecoder().decode(base64) }.getOrElse { error("返回图片编码无效") }
            require(decoded.isNotEmpty() && decoded.size <= MAX_IMAGE_BYTES) { "返回图片为空或过大" }
            return ImageSource(bytes = decoded)
        }
        return ImageSource(url = checkedImageUrl(row.optString("url")))
    }

    fun checkedImageUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull()
        require(parsed != null && parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty()) { "服务返回了无效的图片地址" }
        return parsed.toString()
    }

    fun error(status: Int, body: String, key: String): String {
        val root = runCatching { JSONObject(body) }.getOrNull()
        val reason = root?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: root?.optString("message")?.takeIf { it.isNotBlank() }
        val fallback = when (status) {
            401 -> "API Key 无效，请重新设置"
            402 -> "账户余额不足，请在基元律动充值"
            403 -> "账号没有该模型权限或请求被服务拒绝"
            429 -> "请求过于频繁，请稍后再试"
            404 -> "生图接口暂不可用，请核对服务文档"
            else -> "生图服务请求失败（HTTP $status）"
        }
        // 服务端可能回显请求，避免把凭据带入界面和日志。
        val safe = (reason ?: fallback).let { if (key.isEmpty()) it else it.replace(key, "[已隐藏]") }
            .replace(Regex("(?i)(?:bearer\\s+)?sk[_-][A-Za-z0-9_-]+"), "[已隐藏]").take(300)
        val trace = root?.optString("traceId")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,100}")) }
        return safe + trace?.let { "\n请求编号：$it" }.orEmpty()
    }
}
