package com.videodl.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.videodl.app.data.DownloadTaskEntity
import com.videodl.app.data.TaskRepository
import com.videodl.app.data.TaskStatus
import com.videodl.app.download.DownloadService
import com.videodl.app.ytdlp.DouyinGallery
import com.videodl.app.ytdlp.GallerySelection
import com.videodl.app.ytdlp.ProbeResult
import com.videodl.app.ytdlp.FormatOption
import com.videodl.app.ytdlp.Platform
import com.videodl.app.ytdlp.UrlUtils
import com.videodl.app.ytdlp.YtDlpEngine
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application = application

    // 一次性页面事件，避免下载进度更新或重建页面时重复跳转。
    private val pageEvents = Channel<Int>(Channel.BUFFERED)
    val requestedPage = pageEvents.receiveAsFlow()

    val tasks: StateFlow<List<DownloadTaskEntity>> = TaskRepository.tasks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var inputText by mutableStateOf("")
        private set

    var message by mutableStateOf<String?>(null)
        private set

    var parsing by mutableStateOf(false)
        private set

    var engineVersion by mutableStateOf<String?>(null)
        private set

    var engineError by mutableStateOf<String?>(null)
        private set

    var updatingEngine by mutableStateOf(false)
        private set

    init {
        prepareEngine()
    }

    private fun prepareEngine() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { YtDlpEngine.ensureInitialized(app) }
                engineVersion = withContext(Dispatchers.IO) { YtDlpEngine.versionLabel(app) }
                engineError = null
            } catch (t: Throwable) {
                engineError = t.message ?: "解析引擎初始化失败"
            }
        }
    }

    // ------------------------------------------------------------- input

    fun onInputChange(text: String) {
        inputText = text
    }

    /** 从其他 App 分享进来的文本：把链接并入输入框，交给用户确认后再解析。 */
    fun appendSharedText(text: String?) {
        val urls = UrlUtils.extractUrls(text.orEmpty())
        if (urls.isEmpty()) {
            message = "分享内容里没有识别到视频链接"
            return
        }
        val merged = (UrlUtils.extractUrls(inputText) + urls).distinct()
        inputText = merged.joinToString("\n")
        pageEvents.trySend(0)
        message = "已接收 ${urls.size} 条链接，确认后点「解析链接」"
    }

    fun dismissMessage() {
        message = null
    }

    // ------------------------------------------------------------- parse

    fun addAndParse() {
        if (parsing || updatingEngine) return
        val urls = UrlUtils.extractUrls(inputText)
        if (urls.isEmpty()) {
            message = "没有识别到有效链接，请粘贴 http(s) 开头的视频链接"
            return
        }
        inputText = ""
        parsing = true
        viewModelScope.launch {
            val ids = urls.map { url ->
                val platform = UrlUtils.platformOf(url)
                val page = if (platform == Platform.BILIBILI) {
                    UrlUtils.bilibiliPageIndex(url) ?: 1
                } else {
                    null
                }
                TaskRepository.insert(
                    DownloadTaskEntity(
                        url = url,
                        platform = platform.key,
                        pageIndex = page,
                        status = TaskStatus.PENDING.name,
                    )
                )
            }
            parseAll(ids)
        }
    }

    private suspend fun parseAll(ids: List<Long>) {
        parsing = true
        try {
            ids.forEach { parseOne(it) }
        } finally {
            parsing = false
            pageEvents.trySend(1)
        }
    }

    private suspend fun parseOne(id: Long) {
        val task = TaskRepository.byId(id) ?: return
        if (task.statusEnum == TaskStatus.CANCELED) return

        TaskRepository.setStatus(id, TaskStatus.PARSING)
        try {
            val result = YtDlpEngine.probe(
                context = app,
                url = task.url,
                platform = Platform.fromKey(task.platform),
                pageIndex = task.pageIndex,
            )
            val current = TaskRepository.byId(id) ?: return
            if (current.statusEnum == TaskStatus.CANCELED) return

            saveProbe(current, result)
        } catch (t: Throwable) {
            val current = TaskRepository.byId(id) ?: return
            if (current.statusEnum == TaskStatus.CANCELED) return
            TaskRepository.save(
                current.copy(
                    status = TaskStatus.FAILED.name,
                    errorMessage = t.message ?: "解析失败",
                    speedText = null,
                )
            )
        }
    }

    private suspend fun saveProbe(current: DownloadTaskEntity, result: ProbeResult) {
        val previousHeight = FormatOption.listFromJson(current.formatOptionsJson)
            .firstOrNull { it.selector == current.formatSelector }?.height
        val selected = result.formats.firstOrNull { it.height == previousHeight } ?: result.formats.first()
        val gallery = if (result.resolver == "douyin_gallery")
            runCatching { DouyinGallery.fromJson(result.downloadInfoJson!!) }.getOrNull() else null
        val oldGallery = runCatching { DouyinGallery.fromJson(current.downloadInfoJson!!) }.getOrNull()
        val selection = GallerySelection.decode(current.gallerySelection)?.takeIf {
            gallery != null && gallery.id == oldGallery?.id && it.matches(gallery.images.size)
        }
        TaskRepository.save(current.copy(
            gallerySelection = selection?.encode(),
            status = TaskStatus.READY.name, title = result.title, uploader = result.uploader,
            durationSeconds = result.durationSeconds, thumbnailUrl = result.thumbnailUrl,
            resolvedUrl = result.resolvedUrl, resolver = result.resolver,
            downloadInfoJson = result.downloadInfoJson,
            formatOptionsJson = FormatOption.listToJson(result.formats),
            formatSelector = selected.selector, formatLabel = if (gallery != null)
                selection?.label ?: "请选择图片（共 ${gallery.images.size} 张）" else selected.qualityLabel,
            errorMessage = result.note, progress = 0f, speedText = null, etaSeconds = 0,
        ))
    }

    fun acceptGuestResult(taskId: Long, result: ProbeResult) {
        viewModelScope.launch {
            val current = TaskRepository.byId(taskId) ?: return@launch
            if (current.statusEnum != TaskStatus.FAILED) return@launch
            saveProbe(current, result)
            pageEvents.trySend(1)
            message = "游客解析成功，请选择画质并开始下载"
        }
    }

    // -------------------------------------------------------------- queue

    fun selectFormat(taskId: Long, option: FormatOption) {
        viewModelScope.launch {
            val task = TaskRepository.byId(taskId) ?: return@launch
            TaskRepository.save(
                task.copy(formatSelector = option.selector, formatLabel = option.qualityLabel)
            )
        }
    }

    fun selectGalleryImages(taskId: Long, indices: Set<Int>) {
        viewModelScope.launch {
            val task = TaskRepository.byId(taskId) ?: return@launch
            if (!task.isGallery || task.statusEnum != TaskStatus.READY) return@launch
            val gallery = runCatching { DouyinGallery.fromJson(task.downloadInfoJson!!) }.getOrNull()
                ?: return@launch
            val selection = GallerySelection.create(gallery.images.size, indices) ?: return@launch
            TaskRepository.save(task.copy(gallerySelection = selection.encode(), formatLabel = selection.label))
        }
    }

    fun startDownloads() {
        if (updatingEngine) { message = "引擎更新中，请稍后开始下载"; return }
        viewModelScope.launch {
            val ready = TaskRepository.all().filter { it.statusEnum == TaskStatus.READY && it.canDownload }
            if (ready.isEmpty()) {
                message = "没有可下载的任务，请先解析链接并选择图文图片"
                return@launch
            }
            ready.forEach { TaskRepository.enqueueIfReady(it.id) }
            DownloadService.start(app)
            message = "已加入下载队列，共 ${ready.size} 条"
        }
    }

    fun startDownload(taskId: Long) {
        if (updatingEngine) { message = "引擎更新中，请稍后开始下载"; return }
        viewModelScope.launch {
            val task = TaskRepository.byId(taskId) ?: return@launch
            if (task.statusEnum != TaskStatus.READY) return@launch
            if (!task.canDownload) { message = "请先选择要下载的图片"; return@launch }
            if (!TaskRepository.enqueueIfReady(taskId)) return@launch
            DownloadService.start(app)
        }
    }

    fun cancelTask(taskId: Long) {
        viewModelScope.launch {
            val task = TaskRepository.byId(taskId) ?: return@launch
            when {
                task.statusEnum == TaskStatus.PARSING ->
                    TaskRepository.setStatus(taskId, TaskStatus.CANCELED)

                task.statusEnum == TaskStatus.QUEUED || task.statusEnum.isRunning ->
                    DownloadService.cancelTask(app, taskId)
            }
        }
    }

    fun retryTask(taskId: Long) {
        if (parsing || updatingEngine) { message = "请等待当前解析或更新完成"; return }
        parsing = true
        viewModelScope.launch {
            TaskRepository.resetTo(taskId, TaskStatus.PENDING, null)
            // 平台直链会过期；重试先刷新解析，保留仍可用的画质选择。
            parseAll(listOf(taskId))
        }
    }

    fun removeTask(taskId: Long) {
        viewModelScope.launch {
            val task = TaskRepository.byId(taskId) ?: return@launch
            if (task.statusEnum.isRunning) DownloadService.cancelTask(app, taskId)
            TaskRepository.delete(taskId)
        }
    }

    fun clearFinished() {
        viewModelScope.launch {
            val removed = TaskRepository.deleteFinished()
            message = if (removed > 0) "已清除 $removed 条已结束任务" else "没有已结束的任务"
        }
    }

    fun cancelAll() {
        DownloadService.cancelAll(app)
    }

    // ------------------------------------------------------------- engine

    fun updateEngine() {
        if (updatingEngine || parsing) return
        updatingEngine = true
        viewModelScope.launch {
            try {
                if (TaskRepository.busyCount() > 0) {
                    message = "请等待解析和下载结束后更新引擎"
                    return@launch
                }
                message = "正在从 GitHub 更新 yt-dlp…"
                val version = withContext(Dispatchers.IO) { YtDlpEngine.updateEngine(app).getOrThrow() }
                engineVersion = version
                message = "解析引擎已更新到 $version"
            } catch (t: Throwable) {
                message = "更新失败：${t.message ?: "未知错误"}"
            } finally {
                updatingEngine = false
            }
        }
    }
}
