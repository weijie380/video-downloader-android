package com.videodl.app.imagegen

import com.videodl.app.download.ImageFileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.resume

class TokenRhythmClient(
    private val baseUrl: String = "https://tokenrhythm.studio",
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS).callTimeout(200, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build(),
) {
    suspend fun models(): List<ImageModel> = withContext(Dispatchers.IO) {
        execute(Request.Builder().url("$baseUrl/api/models").build(), limit = 2 * 1024 * 1024) { response, bytes ->
            check(response.isSuccessful) { "模型信息暂不可用" }
            ImageProtocol.parseModels(bytes.toString(Charsets.UTF_8))
        }
    }

    suspend fun generate(key: String, model: String, prompt: String): ByteArray = withContext(Dispatchers.IO) {
        require(key.isNotBlank()) { "请先设置 API Key" }
        val payload = ImageProtocol.request(model, prompt).toString()
        val source = execute(Request.Builder().url("$baseUrl/v1/images/generations")
            .header("Authorization", "Bearer $key")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType())).build(),
            limit = ImageProtocol.MAX_IMAGE_BYTES * 4 / 3 + 4096) { response, bytes ->
            check(response.isSuccessful) { ImageProtocol.error(response.code, bytes.toString(Charsets.UTF_8), key) }
            ImageProtocol.parseImage(bytes.toString(Charsets.UTF_8))
        }
        // 图片 CDN 请求不携带平台 API Key，重定向也不继承鉴权头。
        val image = source.bytes ?: downloadImage(checkNotNull(source.url))
        require(ImageFileType.extension(image) != null) { "服务返回的内容不是有效图片" }
        image
    }

    private suspend fun downloadImage(url: String): ByteArray {
        var current = ImageProtocol.checkedImageUrl(url)
        repeat(5) {
            val result = execute(Request.Builder().url(current).build(), ImageProtocol.MAX_IMAGE_BYTES) { response, bytes ->
                if (response.code in 300..399) {
                    val next = response.header("Location")?.let { response.request.url.resolve(it) }?.toString()
                        ?: error("图片地址重定向失败")
                    ImageProtocol.checkedImageUrl(next) to null
                } else {
                    check(response.isSuccessful) { "图片获取失败（HTTP ${response.code}），请重新生成" }
                    null to bytes
                }
            }
            result.second?.let { return it }
            current = checkNotNull(result.first)
        }
        error("图片地址重定向过多")
    }

    private suspend fun <T> execute(request: Request, limit: Int, read: (Response, ByteArray) -> T): T {
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val body = it.body ?: error("服务返回为空")
                            require(body.contentLength() <= limit) { "服务响应过大" }
                            val buffer = ByteArray(16 * 1024)
                            val output = ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                while (true) {
                                    if (!continuation.isActive) throw IOException("请求已取消")
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    require(output.size() + count <= limit) { "服务响应过大" }
                                    output.write(buffer, 0, count)
                                }
                            }
                            read(it, output.toByteArray())
                        }
                        continuation.resume(result)
                    } catch (t: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(t)
                    }
                }
            })
        }
    }
}
