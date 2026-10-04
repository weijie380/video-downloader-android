package com.videodl.app.imagegen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
        // 留下一张有明确测试文案的本机图供 UI 验证，测试结束不保留密钥。
    }

    @Test fun invalidImageDoesNotCreateHistoryEntry() {
        val store = GeneratedImages(context)
        val before = store.load()
        assertTrue(runCatching { store.add("not an image".toByteArray(), "wan2.7-image", "fixture", before) }.isFailure)
        assertEquals(before, store.load())
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
