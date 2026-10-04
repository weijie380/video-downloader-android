package com.videodl.app.imagegen

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

class TokenRhythmClientTest {
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aP9sAAAAASUVORK5CYII=")

    @Test fun bothModelsUseImageEndpointAndBearer() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = TokenRhythmClient(server.url("/").toString().trimEnd('/'))
            ImageProtocol.models.forEach { model ->
                server.enqueue(MockResponse().setBody(JSONObject().put("data", org.json.JSONArray()
                    .put(JSONObject().put("b64_json", Base64.getEncoder().encodeToString(png)))).toString()))
                assertArrayEquals(png, client.generate("test-key", model.id, " 海边日落 "))
                val request = server.takeRequest()
                assertEquals("/v1/images/generations", request.path)
                assertEquals("Bearer test-key", request.getHeader("Authorization"))
                val body = JSONObject(request.body.readUtf8())
                assertEquals(model.id, body.getString("model"))
                assertEquals("海边日落", body.getString("prompt"))
                assertEquals(1, body.getInt("n"))
                assertFalse(body.has("image"))
            }
        }
    }

    @Test fun rejectedRequestDoesNotRetryOrLeakKey() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"invalid sk_secret123", "traceId":"trace-123"}"""))
            val error = runCatching { TokenRhythmClient(server.url("/").toString().trimEnd('/'))
                .generate("sk_secret123", "qwen-image-2.0", "山水画") }.exceptionOrNull()
            assertNotNull(error)
            assertFalse(error!!.message.orEmpty().contains("sk_secret123"))
            assertTrue(error.message.orEmpty().contains("trace-123"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun stopWaitingCancelsPendingRequestWithoutRetry() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.Default) {
                TokenRhythmClient(server.url("/").toString().trimEnd('/')).generate("test-key", "wan2.7-image", "一棵树")
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun invalidResponseDoesNotSaveAnErrorPage() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"PGh0bWw+ZXJyb3I8L2h0bWw+"}]}"""))
            val error = runCatching { TokenRhythmClient(server.url("/").toString().trimEnd('/'))
                .generate("test-key", "qwen-image-2.0", "山水画") }.exceptionOrNull()
            assertTrue(error!!.message.orEmpty().contains("不是有效图片"))
        }
    }

    @Test fun validatesPromptModelAndHttpsResult() {
        assertTrue(runCatching { ImageProtocol.request("unsupported", "cat") }.isFailure)
        assertTrue(runCatching { ImageProtocol.request("qwen-image-2.0", " ") }.isFailure)
        assertTrue(runCatching { ImageProtocol.request("wan2.7-image", "a".repeat(4001)) }.isFailure)
        assertTrue(runCatching { ImageProtocol.parseImage("""{"data":[{"url":"http://cdn.example/a.png"}]}""") }.isFailure)
        assertTrue(runCatching { ImageProtocol.checkedImageUrl("https://user:secret@cdn.example/a.png") }.isFailure)
        assertEquals("https://cdn.example/a.png", ImageProtocol.parseImage("""{"data":[{"url":"https://cdn.example/a.png"}]}""").url)
    }

    @Test fun missingImageAndBadBase64AreFailures() {
        assertTrue(runCatching { ImageProtocol.parseImage("""{"data":[]}""") }.isFailure)
        assertTrue(runCatching { ImageProtocol.parseImage("""{"data":[{"b64_json":"!!!!"}]}""") }.isFailure)
    }

    @Test fun catalogKeepsOnlyRequestedModelsAndUsesCurrentPrice() {
        val parsed = ImageProtocol.parseModels("""{"data":[{"id":"qwen-image-2.0","status":"online","effectiveAvailable":true,"price":"¥0.20/张"},{"id":"wan2.7-image","status":"offline","effectiveAvailable":false},{"id":"other-image","status":"online"}]}""")
        assertEquals(2, parsed.size)
        assertEquals("¥0.20/张", parsed.first().price)
        assertTrue(parsed.first().available)
        assertFalse(parsed.last().available)
    }

    @Test fun cdnAndRedirectNeverReceiveApiKey() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val transport = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture")
            when (request.url.host) {
                "tokenrhythm.studio" -> response.code(200).body("""{"data":[{"url":"https://images.example/start"}]}""".toResponseBody())
                "images.example" -> response.code(302).header("Location", "https://cdn.example/image.png").body(ByteArray(0).toResponseBody())
                "cdn.example" -> response.code(200).body(png.toResponseBody())
                else -> error("unexpected request")
            }.build()
        }.build()
        assertArrayEquals(png, TokenRhythmClient(client = transport).generate("test-key", "wan2.7-image", "一棵树"))
        assertEquals(3, requests.size)
        assertEquals("Bearer test-key", requests.first().header("Authorization"))
        requests.drop(1).forEach { assertNull(it.header("Authorization")); assertNull(it.header("Cookie")) }
    }

    @Test fun cdnCannotRedirectToPlainHttp() = runBlocking {
        var count = 0
        val transport = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            count++
            val request = chain.request()
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture")
            if (count == 1) response.code(200).body("""{"data":[{"url":"https://images.example/start"}]}""".toResponseBody())
            else response.code(302).header("Location", "http://cdn.example/image.png").body(ByteArray(0).toResponseBody())
            response.build()
        }.build()
        assertTrue(runCatching { TokenRhythmClient(client = transport).generate("test-key", "wan2.7-image", "一棵树") }.isFailure)
        assertEquals(2, count)
    }
}
