package com.videodl.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.videodl.app.download.MediaStoreExporter
import com.videodl.app.imagegen.*
import kotlinx.coroutines.*
import java.io.File
import java.net.SocketTimeoutException
import java.io.IOException

class ImageGenerationViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val client = TokenRhythmClient()
    private val store = GeneratedImages(app)
    private var generation: Job? = null
    var models by mutableStateOf(ImageProtocol.models)
        private set
    var selectedModel by mutableStateOf(ImageProtocol.models.first().id)
        private set
    var prompt by mutableStateOf("")
        private set
    var hasKey by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var ready by mutableStateOf(false)
        private set
    var images by mutableStateOf<List<GeneratedImage>>(emptyList())
        private set
    var saving by mutableStateOf<String?>(null)
        private set
    var savedIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var catalogMessage by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            try {
                images = withContext(Dispatchers.IO) { store.load() }
                hasKey = withContext(Dispatchers.IO) { ImageApiKey.read(app).isNotBlank() }
            } catch (t: Exception) { message = t.message ?: "生图设置读取失败" }
            finally { ready = true }
        }
    }

    // 仅进入生图页时刷新公开价格信息。
    fun refreshModels() {
        viewModelScope.launch {
            try { models = client.models(); catalogMessage = null }
            catch (t: CancellationException) { throw t }
            catch (_: Exception) { catalogMessage = "模型价格暂不可用，费用以基元律动账户结算为准。" }
        }
    }
    fun selectModel(id: String) { if (!busy) selectedModel = id }
    fun changePrompt(value: String) { prompt = value.take(4000) }

    fun saveKey(value: String, onSaved: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { ImageApiKey.save(app, value) }
                hasKey = true
                message = "API Key 已加密保存在此手机"
                onSaved()
            } catch (t: Exception) { onError(t.message ?: "密钥保存失败") }
        }
    }
    fun clearKey(onCleared: () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { ImageApiKey.clear(app) }
                hasKey = false
                onCleared()
            } catch (t: Exception) { message = t.message ?: "密钥清除失败" }
        }
    }

    fun generate() {
        if (busy || !ready) return
        val input = prompt.trim()
        if (!hasKey) { message = "请先设置基元律动 API Key"; return }
        if (input.isEmpty()) { message = "请先输入图片描述"; return }
        if (models.none { it.id == selectedModel && it.available }) { message = "该模型暂不可用，请切换模型"; return }
        val model = selectedModel
        busy = true
        message = null
        generation = viewModelScope.launch {
            try {
                val key = withContext(Dispatchers.IO) { ImageApiKey.read(app) }
                val bytes = client.generate(key, model, input)
                ensureActive()
                // 写图和原子索引一起完成，取消不能留下不完整记录。
                val image = withContext(NonCancellable + Dispatchers.IO) { store.add(bytes, model, input, images) }
                images = listOf(image) + images
                message = "图片已生成，点击预览或保存到下载目录"
            } catch (t: CancellationException) { throw t }
            catch (_: SocketTimeoutException) { message = "生图请求超时，可能已在服务端处理。请先查看基元律动调用记录，再决定是否重新生成。" }
            catch (_: IOException) { message = "网络连接中断。请检查网络，并在基元律动调用记录确认是否已生成；软件不会自动重复提交。" }
            catch (t: Exception) { message = t.message ?: "生成失败，请稍后再试" }
            finally { busy = false }
        }
    }
    fun cancel() {
        generation?.cancel()
        message = "已停止等待；已提交的服务端任务和计费可能继续，请查看基元律动调用记录。"
    }

    fun saveImage(image: GeneratedImage) {
        if (saving != null) return
        saving = image.id
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val file = File(image.path)
                    MediaStoreExporter.export(app, file, "${image.model}-${image.id.take(8)}.${file.extension}",
                        MediaStoreExporter.mimeTypeFor(file.extension), subFolder = "生图", replaceExisting = false)
                }
                savedIds = savedIds + image.id
                message = "已保存到 下载/视频下载器/生图"
            } catch (t: Exception) { message = t.message ?: "保存失败，请检查剩余空间" }
            finally { saving = null }
        }
    }
}
