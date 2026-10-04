package com.videodl.app.imagegen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.content.ContextWrapper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodl.app.download.MediaStoreExporter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class ImageStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun keyIsEncryptedPersistsAndCanBeCleared() {
        val key = "sk_fixture-only-not-a-real-key"
        try {
            ImageApiKey.save(context, key)
            assertEquals(key, ImageApiKey.read(context))
            val xml = File(context.applicationInfo.dataDir, "shared_prefs/tokenrhythm-image-api.xml").readText()
            assertFalse(xml.contains(key))
            assertTrue(xml.contains("encrypted"))
            ImageApiKey.save(context, "sk_other-fixture-key")
            assertEquals("sk_other-fixture-key", ImageApiKey.read(context))
        } finally { ImageApiKey.clear(context) }
        assertEquals("", ImageApiKey.read(context))
    }

    @Test fun generatedImageRestoresAndExportsIdenticalBytes() {
        val bytes = fixture()
        val store = GeneratedImages(context)
        val item = store.add(bytes, "qwen-image-2.0", "本机测试画面，用于验证预览和保存", store.load())
        assertEquals(item.id, GeneratedImages(context).load().first().id)
        assertArrayEquals(bytes, File(item.path).readBytes())
        val exported = MediaStoreExporter.export(context, File(item.path), "image-storage-fixture.png", "image/png",
            subFolder = "生图", replaceExisting = false)
        try {
            val saved = context.contentResolver.openInputStream(exported.uri)!!.use { it.readBytes() }
            assertArrayEquals(bytes, saved)
            assertTrue(MediaStoreExporter.stillExists(context, exported.uri))
        } finally { MediaStoreExporter.remove(context, listOf(exported)) }
        val next = store.add(bytes, "qwen-image-2.0", "第二轮本机测试：继续修改", store.load(), item.conversationId, item.id)
        assertEquals(item.id, GeneratedImages(context).load().first { it.id == next.id }.parentId)
        assertEquals(2, GeneratedImages(context).load().count { it.conversationId == item.conversationId })
        // 留下一张有明确测试文案的本机图供 UI 验证，测试结束不保留密钥。
    }

    @Test fun invalidImageDoesNotCreateHistoryEntry() {
        val store = GeneratedImages(context)
        val before = store.load()
        assertTrue(runCatching { store.add("not an image".toByteArray(), "wan2.7-image", "fixture", before) }.isFailure)
        assertEquals(before, store.load())
    }

    @Test fun deletingRoundAndConversationPersistsAndKeepsExportedImage() {
        val directory = File(context.cacheDir, "chat-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = directory }
        val store = GeneratedImages(isolated)
        val first = store.add(fixture(), "qwen-image-2.0", "第一轮", emptyList(), "chat-a")
        val second = store.add(fixture(), "qwen-image-2.0", "第二轮", listOf(first), "chat-a", first.id)
        val other = store.add(fixture(), "wan2.7-image", "其他对话", listOf(second, first), "chat-b")
        val exported = MediaStoreExporter.export(context, File(first.path), "delete-chat-fixture.png", "image/png", subFolder = "生图", replaceExisting = false)
        try {
            var loaded = GeneratedImages(isolated).load()
            assertEquals(first.id, loaded.first { it.id == second.id }.parentId)
            assertEquals("chat-a", loaded.first { it.id == second.id }.conversationId)
            loaded = store.delete(setOf(second.id), loaded)
            assertFalse(File(second.path).exists())
            assertTrue(File(first.path).exists())
            assertEquals(loaded, GeneratedImages(isolated).load())
            loaded = store.delete(setOf(first.id), loaded)
            assertFalse(File(first.path).exists())
            assertEquals(listOf(other), GeneratedImages(isolated).load())
            assertTrue(MediaStoreExporter.stillExists(context, exported.uri))
            assertArrayEquals(fixture(), context.contentResolver.openInputStream(exported.uri)!!.use { it.readBytes() })
            store.delete(setOf(other.id), loaded)
            assertTrue(GeneratedImages(isolated).load().isEmpty())
        } finally {
            MediaStoreExporter.remove(context, listOf(exported))
            directory.deleteRecursively()
        }
    }

    @Test fun legacyImagesMigrateToIndependentConversationsWithoutLosingFiles() {
        val directory = File(context.cacheDir, "legacy-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = directory }
        try {
            val folder = File(directory, "generated-images").apply { mkdirs() }
            File(folder, "old.png").writeBytes(fixture())
            File(folder, "history.json").writeText(JSONArray().put(JSONObject().put("id", "old-id")
                .put("model", "qwen-image-2.0").put("prompt", "旧版记录").put("file", "old.png").put("created", 1)).toString())
            val store = GeneratedImages(isolated)
            val old = store.load().single()
            assertEquals("old-id", old.conversationId)
            assertNull(old.parentId)
            val next = store.add(fixture(), old.model, "继续修改", listOf(old), old.conversationId, old.id)
            assertEquals(2, GeneratedImages(isolated).load().size)
            assertEquals(old.id, next.parentId)
            assertTrue(File(old.path).exists())
        } finally { directory.deleteRecursively() }
    }

    private fun fixture(): ByteArray {
        val bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(61, 67, 103))
        paint.color = Color.rgb(252, 183, 129)
        canvas.drawCircle(420f, 135f, 60f, paint)
        paint.color = Color.rgb(55, 116, 151)
        canvas.drawRect(0f, 210f, 640f, 400f, paint)
        paint.color = Color.rgb(176, 190, 220)
        canvas.drawRect(80f, 160f, 245f, 300f, paint)
        paint.color = Color.rgb(43, 62, 90)
        canvas.drawRect(95f, 180f, 230f, 285f, paint)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        return output.toByteArray()
    }
}
