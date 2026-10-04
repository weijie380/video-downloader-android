package com.videodl.app.download

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.videodl.app.MainActivity
import com.videodl.app.R
import com.videodl.app.data.DownloadTaskEntity
import com.videodl.app.data.TaskRepository
import com.videodl.app.data.TaskStatus
import com.videodl.app.data.SavedAsset
import com.videodl.app.ytdlp.GallerySelection
import com.videodl.app.ytdlp.DouyinGallery
import com.videodl.app.ytdlp.DouyinGuestParser
import com.videodl.app.ytdlp.FormatOption
import com.videodl.app.ytdlp.RequestPolicy
import com.videodl.app.ytdlp.Platform
import com.videodl.app.ytdlp.UrlUtils
import com.videodl.app.ytdlp.YtDlpEngine
import com.videodl.app.ytdlp.DownloadRecovery
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 两路并发下载服务，FFmpeg 后处理使用跨进程锁单独排队。
 *
 * 设计要点：
 *  - 队列状态存在 Room 里，服务只负责「取出下一个 QUEUED 任务并执行」；
 *  - 数据库事务原子领取，最多两个独立的 yt-dlp 进程；
 *  - 取消通过 YoutubeDL.destroyProcessById 杀掉 yt-dlp 子进程；
 *  - 完成后经 MediaStore 落到系统「下载/视频下载器」。
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workerLock = Any()
    private var workerJob: Job? = null
    private val activeJobs = ConcurrentHashMap<Long, Job>()
    @Volatile private var destroyed = false

    private val lastProgressWrite = ConcurrentHashMap<Long, Long>()
    private val activeProcessIds = ConcurrentHashMap<Long, String>()
    private val activeGalleries = ConcurrentHashMap<Long, DouyinImageDownloader>()
    private data class Progress(val title: String, val percent: Float, val speed: String?, val eta: Long)
    private val notificationProgress = ConcurrentHashMap<Long, Progress>()
    private val lastNotificationWrite = mutableMapOf<Long, Long>()
    private var lastSummaryWrite = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        YtDlpEngine.ensureInitialized(this)
        SerialFFmpeg.install(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground("准备下载…")

        when (intent?.action) {
            ACTION_CANCEL_TASK -> {
                val id = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                if (id > 0) scope.launch { cancelTask(id) }
            }

            ACTION_CANCEL_ALL -> scope.launch { cancelAll() }
        }

        ensureWorker()
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        notificationProgress.keys.forEach { NotificationManagerCompat.from(this).cancel(PROGRESS_TAG, it.hashCode()) }
        notificationProgress.clear()
        activeProcessIds.values.forEach { processId ->
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
        }
        activeGalleries.values.forEach { it.cancel() }
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- queue

    private fun ensureWorker() {
        synchronized(workerLock) {
            if (workerJob?.isActive == true) return
            val job = scope.launch(start = CoroutineStart.LAZY) { runQueue() }
            workerJob = job
            job.invokeOnCompletion {
                synchronized(workerLock) { if (workerJob === job) workerJob = null }
                scope.launch {
                    if (TaskRepository.hasQueuedOrRunning()) ensureWorker() else retireIfIdle()
                }
            }
            job.start()
        }
    }

    private suspend fun runQueue() {
        DownloadWorkers.run(TaskRepository::nextQueued, TaskRepository::hasQueuedOrRunning) { task ->
            coroutineScope {
                val job = launch(start = CoroutineStart.LAZY) { process(task) }
                activeJobs[task.id] = job
                try { job.start(); job.join() } finally { activeJobs.remove(task.id, job) }
            }
        }
    }

    private suspend fun process(task: DownloadTaskEntity) {
        val tmpDir = File(cacheDir, "downloads/task_${task.id}")
        val processId = "task-${task.id}"
        activeProcessIds[task.id] = processId
        val wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "videodl:download")
        var exportedVideo: Saved? = null
        var videoCompleted = false

        try {
            wakeLock.acquire(6 * 60 * 60 * 1000L)
            tmpDir.deleteRecursively()
            tmpDir.mkdirs()

            currentCoroutineContext().ensureActive()
            if (TaskRepository.byId(task.id)?.statusEnum != TaskStatus.DOWNLOADING) return
            updateNotification(task.id, task.title ?: task.url, 0f, null, 0L)

            var downloadTask = task
            if (Platform.fromKey(task.platform) == Platform.DOUYIN) {
                val refreshed = try { DouyinGuestParser.probe(task.resolvedUrl ?: task.url) }
                    catch (e: Exception) {
                        if (task.downloadInfoJson.isNullOrBlank()) throw e
                        null // 官方页暂时不给 SSR 数据时，使用刚在 WebView 解析出的媒体地址。
                    }
                if (TaskRepository.byId(task.id)?.statusEnum == TaskStatus.CANCELED) return
                if (refreshed?.resolver == "douyin_gallery" || (refreshed == null && task.isGallery)) {
                    processGallery(task.copy(resolvedUrl = refreshed?.resolvedUrl ?: task.resolvedUrl,
                        resolver = "douyin_gallery", downloadInfoJson = refreshed?.downloadInfoJson ?: task.downloadInfoJson,
                        title = refreshed?.title ?: task.title), tmpDir)
                    return
                }
                val previous = FormatOption.listFromJson(task.formatOptionsJson)
                    .firstOrNull { it.selector == task.formatSelector }
                val selected = if (refreshed != null) refreshed.formats.firstOrNull { it.height == previous?.height }
                    ?: throw IllegalStateException("原画质已不可用，请重试解析并重新选择画质")
                    else previous ?: throw IllegalStateException("请重新选择画质")
                val json = refreshed?.downloadInfoJson ?: task.downloadInfoJson
                    ?: throw IllegalStateException("分享页未返回下载信息")
                File(tmpDir, "share-info.json").writeText(DouyinGuestParser.downloadInfo(json))
                downloadTask = task.copy(resolvedUrl = refreshed?.resolvedUrl ?: task.resolvedUrl,
                    resolver = "douyin_share", formatSelector = selected.selector)
            }
            if (TaskRepository.byId(task.id)?.statusEnum == TaskStatus.CANCELED) return
            val response = downloadWithRecovery(downloadTask, tmpDir, processId)
            if (response.exitCode != 0) {
                throw YoutubeDLException(response.err.ifBlank { "yt-dlp 退出码 ${response.exitCode}" })
            }

            val produced = findProducedFile(tmpDir)
                ?: throw IllegalStateException("下载已结束，但没有找到任何输出文件")

            if (!TaskRepository.beginSaving(task.id)) throw CancellationException("下载已取消")
            updateNotification(task.id, "正在保存到下载目录…", 1f, null, 0L)

            val exported = saveToDownloads(task, produced)
            exportedVideo = exported

            videoCompleted = withContext(NonCancellable) { TaskRepository.complete(
                task.copy(
                    status = TaskStatus.COMPLETED.name,
                    progress = 1f,
                    speedText = null,
                    etaSeconds = 0,
                    errorMessage = null,
                    outputFileName = exported.displayName,
                    outputUri = exported.uri.toString(),
                    outputBytes = exported.bytes,
                    outputMp4 = exported.isMp4,
                    outputNote = exported.note,
                )
            ) }
            if (!videoCompleted) {
                throw CancellationException("下载已取消")
            }
            notifyResult(task.id, task.title ?: exported.displayName, true, exported.note)
        } catch (e: YoutubeDL.CanceledException) {
            markCanceled(task.id)
        } catch (e: InterruptedException) {
            markCanceled(task.id)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { markCanceled(task.id) }
        } catch (t: Throwable) {
            if (TaskRepository.byId(task.id)?.statusEnum != TaskStatus.CANCELED) markFailed(task.id, t)
        } finally {
            if (!videoCompleted) exportedVideo?.let { runCatching { contentResolver.delete(it.uri, null, null) } }
            if (wakeLock.isHeld) wakeLock.release()
            activeProcessIds.remove(task.id)
            lastProgressWrite.remove(task.id)
            removeProgressNotification(task.id)
            tmpDir.deleteRecursively()
        }
    }

    private suspend fun processGallery(task: DownloadTaskEntity, tmpDir: File) {
        val gallery = DouyinGallery.fromJson(task.downloadInfoJson ?: error("图文信息已过期，请重新解析"))
        val selection = GallerySelection.decode(task.gallerySelection)
            ?: error("请先选择要下载的图片")
        require(selection.matches(gallery.images.size)) { "作品图片数量已变化，请重新解析并选择图片" }
        require(selection.indices.isNotEmpty()) { "请先选择要下载的图片" }
        val downloader = DouyinImageDownloader()
        activeGalleries[task.id] = downloader
        val exported = mutableListOf<MediaStoreExporter.Exported>()
        var completed = false
        suspend fun checkCanceled() {
            currentCoroutineContext().ensureActive()
            downloader.checkCanceled()
            if (TaskRepository.byId(task.id)?.statusEnum == TaskStatus.CANCELED) {
                downloader.cancel()
                throw CancellationException("图文下载已取消")
            }
        }
        try {
            val images = mutableListOf<File>()
            var downloaded = 0L
            selection.indices.forEachIndexed { position, index ->
                val image = gallery.images[index]
                checkCanceled()
                val detail = "正在下载第 ${index + 1} 张（${position + 1}/${selection.indices.size}）"
                TaskRepository.updateProgress(task.id, TaskStatus.DOWNLOADING,
                    position.toFloat() / selection.indices.size, downloaded, 0, detail, 0)
                updateNotification(task.id, detail, position.toFloat() / selection.indices.size, null, 0)
                val file = downloader.download(image, tmpDir, index, gallery.shareUrl)
                downloaded += file.length()
                images += file
            }
            checkCanceled()
            val caption = File(tmpDir, "文案.txt").apply {
                writeText("${gallery.caption}\n\n作者：${gallery.author}\n来源：${gallery.shareUrl}\n", Charsets.UTF_8)
            }
            val folder = "${MediaStoreExporter.sanitize(task.title ?: "抖音图文").take(50)} [${gallery.id}]"
            if (!TaskRepository.beginSaving(task.id)) throw CancellationException("图文保存已取消")
            updateNotification(task.id, "正在保存 ${images.size} 张图片及文案…", 1f, null, 0)
            val assets = (images + caption).map { file ->
                checkCanceled()
                val mime = MediaStoreExporter.mimeTypeFor(file.extension)
                val result = MediaStoreExporter.export(this, file, file.name, mime,
                    subFolder = folder, pending = true, replaceExisting = false)
                exported += result
                SavedAsset(result.uri.toString(), result.displayName, mime, file.length())
            }
            checkCanceled()
            MediaStoreExporter.publish(this, exported)
            checkCanceled()
            val note = "${images.size} 张图片及文案 · 下载/视频下载器/$folder"
            completed = withContext(NonCancellable) { TaskRepository.complete(task.copy(status = TaskStatus.COMPLETED.name,
                progress = 1f, speedText = null, etaSeconds = 0, errorMessage = null,
                outputFileName = "${images.size} 张图片及文案", outputUri = assets.first().uri,
                outputFilesJson = SavedAsset.toJson(assets), outputBytes = assets.sumOf { it.bytes },
                outputMp4 = false, outputNote = note, downloadedBytes = downloaded,
                formatLabel = selection.label, downloadInfoJson = gallery.toJson())) }
            if (!completed) throw CancellationException("图文保存已取消")
            notifyResult(task.id, task.title ?: "抖音图文", true, note)
        } finally {
            if (!completed) MediaStoreExporter.remove(this, exported)
            activeGalleries.remove(task.id)
        }
    }

    // ------------------------------------------------------------- download

    private suspend fun downloadWithRecovery(
        task: DownloadTaskEntity, tmpDir: File, processId: String,
    ): com.yausername.youtubedl_android.YoutubeDLResponse {
        try {
            return downloadWithFallback(task, tmpDir, processId).also {
                if (it.exitCode != 0) throw YoutubeDLException(it.err)
            }
        } catch (first: YoutubeDLException) {
            if (Platform.fromKey(task.platform) != Platform.X ||
                !DownloadRecovery.shouldRefresh(first.message)) throw first
            currentCoroutineContext().ensureActive()
            if (TaskRepository.byId(task.id)?.statusEnum == TaskStatus.CANCELED)
                throw CancellationException("下载已取消")
            // 再执行会重新提取媒体地址；保留非空 .part 与分片状态用于续传。
            // 零字节成品会被 --no-overwrites 误认为已完成，必须先清理。
            tmpDir.listFiles()?.filter { it.length() == 0L &&
                (it.extension.lowercase() in MEDIA_EXTENSIONS || it.name.endsWith(".part")) }
                ?.forEach { it.delete() }
            TaskRepository.updateProgress(task.id, TaskStatus.DOWNLOADING, 0f, 0, 0,
                "连接中断，正在重新获取媒体地址（1/1）", 0)
            updateNotification(task.id, "正在恢复下载（1/1）…", 0f, null, 0)
            delay(1500)
            if (TaskRepository.byId(task.id)?.statusEnum == TaskStatus.CANCELED)
                throw CancellationException("下载已取消")
            return try {
                downloadWithFallback(task, tmpDir, processId).also {
                    if (it.exitCode != 0) throw YoutubeDLException(it.err)
                }
            } catch (second: YoutubeDLException) {
                throw YoutubeDLException("${first.message}\n恢复下载仍失败：\n${second.message}")
            }
        }
    }

    private suspend fun downloadWithFallback(
        task: DownloadTaskEntity,
        tmpDir: File,
        processId: String,
    ): com.yausername.youtubedl_android.YoutubeDLResponse {
        return try {
            runDownload(task, tmpDir, processId, mergeIntoMp4 = true)
        } catch (e: YoutubeDLException) {
            val message = e.message.orEmpty()
            if (!looksLikeContainerFailure(message)) throw e

            // 源格式无法封装进 MP4：去掉 --merge-output-format 再跑一次，
            // 复用已下载的媒体流（--continue/--no-overwrites），保留原始可播放容器。
            TaskRepository.setStatus(task.id, TaskStatus.DOWNLOADING)
            runDownload(task, tmpDir, processId, mergeIntoMp4 = false)
        }
    }

    private fun runDownload(
        task: DownloadTaskEntity,
        tmpDir: File,
        processId: String,
        mergeIntoMp4: Boolean,
    ) = YtDlpEngine.withEngine {
        YoutubeDL.getInstance().execute(buildRequest(task, tmpDir, mergeIntoMp4), processId) { percent, eta, line ->
            onDownloadLine(task.id, percent, eta, line)
        }
    }

    private fun buildRequest(
        task: DownloadTaskEntity,
        tmpDir: File,
        mergeIntoMp4: Boolean,
    ): YoutubeDLRequest {
        val platform = Platform.fromKey(task.platform)
        // B 站分 P 只能靠 --playlist-items 指定：URL 上带 ?p=N 时 extractor 取不到 cid（400）
        val url = if (platform == Platform.BILIBILI) {
            UrlUtils.withoutBilibiliPage(task.resolvedUrl ?: task.url)
        } else {
            task.resolvedUrl ?: task.url
        }

        val request = if (platform == Platform.DOUYIN) YoutubeDLRequest(emptyList<String>()) else YoutubeDLRequest(url)
        return request.apply {
            RequestPolicy.apply(this@DownloadService, this, platform, task.resolver, tmpDir)
            DownloadRecovery.configure(this)
            SerialFFmpeg.configure(this@DownloadService, this)
            if (platform == Platform.DOUYIN) addOption("--load-info-json", File(tmpDir, "share-info.json").absolutePath)
            addOption("--newline")
            addOption("--no-mtime")
            addOption("--continue")
            addOption("--no-overwrites")
            addOption("--trim-filenames", 120)
            addOption("--no-cache-dir")

            task.formatSelector?.takeIf { it.isNotBlank() }?.let { selected ->
                val height = FormatOption.listFromJson(task.formatOptionsJson)
                    .firstOrNull { it.selector == selected }?.height ?: 0
                addOption("-f", if (platform == Platform.X)
                    DownloadRecovery.xSelector(height, selected) else selected)
            }
            // 媒体地址刷新后可能从 HLS 切到 HTTP；不同格式不可拼接同一 .part。
            addOption("-o", File(tmpDir, "%(title).80s [%(id)s] [%(format_id)s].%(ext)s").absolutePath)

            if (mergeIntoMp4) addOption("--merge-output-format", "mp4")

            if (platform == Platform.BILIBILI) {
                addOption("--playlist-items", (task.pageIndex ?: 1).coerceAtLeast(1))
            } else {
                addOption("--no-playlist")
            }
        }
    }

    private fun onDownloadLine(taskId: Long, percent: Float, etaSeconds: Long, line: String) {
        val now = SystemClock.elapsedRealtime()
        val last = lastProgressWrite[taskId] ?: 0L
        val isFinal = percent >= 100f
        if (!isFinal && now - last < PROGRESS_INTERVAL_MS) return
        lastProgressWrite[taskId] = now

        val speed = SPEED_REGEX.find(line)?.groupValues?.getOrNull(1)?.trim()
        val total = TOTAL_SIZE_REGEX.find(line)?.let { match ->
            val value = match.groupValues[1].toDoubleOrNull() ?: return@let 0L
            (value * unitFactor(match.groupValues[2])).toLong()
        } ?: 0L
        val stage = if (line.contains("[Merger]") || line.contains("[ffmpeg]", true)) {
            TaskStatus.MERGING
        } else {
            TaskStatus.DOWNLOADING
        }

        scope.launch {
            val current = TaskRepository.byId(taskId) ?: return@launch
            if (!TaskRepository.updateProgress(taskId, stage, (percent / 100f).coerceIn(0f, 1f),
                    (total * percent / 100.0).toLong(), total, speed, etaSeconds)) return@launch
            updateNotification(taskId, current.title ?: current.url, (percent / 100f).coerceIn(0f, 1f), speed, etaSeconds)
        }
    }

    // ---------------------------------------------------------------- save

    private data class Saved(
        val uri: android.net.Uri,
        val displayName: String,
        val bytes: Long,
        val isMp4: Boolean,
        val note: String?,
    )

    private fun saveToDownloads(task: DownloadTaskEntity, produced: File): Saved {
        val extension = produced.extension.lowercase(Locale.ROOT)
        val isMp4 = extension == "mp4"
        val mime = MediaStoreExporter.mimeTypeFor(extension)

        val base = task.title?.takeIf { it.isNotBlank() }
            ?: produced.nameWithoutExtension
        val quality = task.formatLabel?.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty()
        val page = task.pageIndex?.takeIf { it > 1 }?.let { " P$it" }.orEmpty()

        val displayName = "${MediaStoreExporter.sanitize(base)}$quality$page.$extension"
        val exported = MediaStoreExporter.export(this, produced, displayName, mime, replaceExisting = false)

        val note = buildString {
            if (task.pageIndex != null && task.pageIndex > 1) append("B站第 ${task.pageIndex} P · ")
            if (!isMp4) {
                append("源格式无法无损封装为 MP4，已保存为 ${extension.uppercase(Locale.ROOT)}（可正常播放）")
            } else if (task.formatSelector?.contains("bestvideo") == true) {
                append("已用 FFmpeg 合并音视频")
            }
        }.trimEnd(' ', '·').takeIf { it.isNotBlank() }

        return Saved(
            uri = exported.uri,
            displayName = exported.displayName,
            bytes = produced.length(),
            isMp4 = isMp4,
            note = note,
        )
    }

    // -------------------------------------------------------------- cancel

    private suspend fun cancelTask(id: Long) {
        val task = TaskRepository.byId(id) ?: return
        if (task.statusEnum.isFinished) return

        // 先保护数据库状态，再停止这一条的进程/协程，不影响另一条任务。
        if (TaskRepository.cancelIfActive(id) == 0) return
        stopTaskWork(id)
    }

    private fun stopTaskWork(id: Long) {
        removeProgressNotification(id)
        activeJobs[id]?.cancel()

        activeProcessIds[id]?.let { processId ->
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
        }
        activeGalleries[id]?.cancel()
    }

    private suspend fun cancelAll() {
        val ids = TaskRepository.all().filter { it.statusEnum in setOf(TaskStatus.QUEUED,
            TaskStatus.DOWNLOADING, TaskStatus.MERGING, TaskStatus.SAVING) }.map { it.id }
        // 一次更新所有队列状态，避免取消途中空位又启动后续任务。
        if (ids.isNotEmpty()) TaskRepository.cancelBatch(ids)
        ids.forEach { stopTaskWork(it) }
    }

    private suspend fun markCanceled(id: Long) {
        TaskRepository.cancelIfActive(id)
    }

    private suspend fun markFailed(id: Long, error: Throwable) {
        val reason = when (error) {
            is YoutubeDLException -> YtDlpEngine.readable(error.message)
            else -> error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
        }
        val task = TaskRepository.byId(id) ?: return
        if (!TaskRepository.failIfActive(id, reason)) return
        notifyResult(id, task.title ?: task.url, false, reason)
    }

    // ----------------------------------------------------------- helpers

    private fun findProducedFile(dir: File): File? {
        val files = dir.listFiles()?.filter { it.isFile && it.length() > 0 } ?: return null
        val media = files.filter { it.extension.lowercase(Locale.ROOT) in MEDIA_EXTENSIONS }
        if (media.isEmpty()) return null
        val merged = media.filterNot { INTERMEDIATE_NAME.containsMatchIn(it.name) }
        return (merged.ifEmpty { media }).maxByOrNull { it.length() }
    }

    private fun looksLikeContainerFailure(message: String): Boolean {
        val text = message.lowercase(Locale.ROOT)
        return text.contains("could not write header") ||
            text.contains("unsupported codec") ||
            text.contains("not compatible") ||
            text.contains("error opening output") ||
            text.contains("could not find codec parameters") ||
            (text.contains("merg") && (text.contains("fail") || text.contains("error")))
    }

    private suspend fun retireIfIdle() {
        val busy = TaskRepository.hasQueuedOrRunning()
        if (busy) return
        withContext(Dispatchers.Main) {
            if (synchronized(workerLock) { workerJob != null } || destroyed) return@withContext
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // -------------------------------------------------------- notification

    private fun startAsForeground(text: String) {
        val notification = buildNotification(text, 0f, null, 0L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Synchronized
    private fun updateNotification(taskId: Long, title: String, percent: Float, speed: String?, eta: Long) {
        if (destroyed) return
        notificationProgress[taskId] = Progress(title, percent, speed, eta)
        val now = SystemClock.elapsedRealtime()
        if (now - (lastNotificationWrite[taskId] ?: 0L) < 1000L) return
        lastNotificationWrite[taskId] = now
        runCatching {
            val manager = NotificationManagerCompat.from(this)
            manager.notify(PROGRESS_TAG, taskId.hashCode(), buildNotification(title, percent, speed, eta, taskId))
            if (now - lastSummaryWrite >= 1000L) {
                lastSummaryWrite = now
                manager.notify(NOTIFICATION_ID, buildNotification("准备下一条下载…", 0f, null, 0))
            }
        }
    }

    @Synchronized
    private fun removeProgressNotification(taskId: Long) {
        notificationProgress.remove(taskId)
        lastNotificationWrite.remove(taskId)
        runCatching {
            val manager = NotificationManagerCompat.from(this)
            manager.cancel(PROGRESS_TAG, taskId.hashCode())
            if (!destroyed) manager.notify(NOTIFICATION_ID, buildNotification("准备下一条下载…", 0f, null, 0))
        }
    }

    private fun buildNotification(
        title: String,
        percent: Float,
        speed: String?,
        eta: Long,
        taskId: Long? = null,
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this,
            taskId?.hashCode() ?: 1,
            Intent(this, DownloadService::class.java).apply {
                setAction(if (taskId == null) ACTION_CANCEL_ALL else ACTION_CANCEL_TASK)
                if (taskId != null) { putExtra(EXTRA_TASK_ID, taskId); data = Uri.parse("videodl://task/$taskId") }
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val progress = (percent.coerceIn(0f, 1f) * 100).toInt()
        val detail = buildString {
            append("$progress%")
            speed?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
            if (eta > 0) append(" · 剩余 ${formatEta(eta)}")
        }

        val lines = notificationProgress.entries.sortedBy { it.key }.map { (_, item) ->
            "${item.title.take(32)} · ${(item.percent.coerceIn(0f, 1f) * 100).toInt()}%" +
                item.speed?.let { " · $it" }.orEmpty()
        }
        return NotificationCompat.Builder(this, com.videodl.app.VideoDlApp.CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (taskId == null && lines.isNotEmpty()) "下载进行中 · ${lines.size}/${DownloadWorkers.LIMIT}" else "正在下载")
            .setContentText(if (taskId == null && lines.isNotEmpty()) lines.joinToString("\n") else title.take(60))
            .setSubText(if (taskId == null) "最多同时下载 ${DownloadWorkers.LIMIT} 条" else detail)
            .setStyle(if (taskId == null) NotificationCompat.InboxStyle().also { style -> lines.forEach { style.addLine(it) } } else null)
            .setContentIntent(open)
            .addAction(0, if (taskId == null) "全部取消" else "取消此条", cancel)
            .setGroup("videodl.downloads")
            .setGroupSummary(taskId == null)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, percent <= 0f)
            .build()
    }

    private fun notifyResult(taskId: Long, title: String, success: Boolean, extra: String?) {
        val open = PendingIntent.getActivity(
            this,
            taskId.toInt(),
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (success) {
            extra?.takeIf { it.isNotBlank() } ?: "已保存到「下载/视频下载器」"
        } else {
            extra?.takeIf { it.isNotBlank() } ?: "下载失败"
        }
        val notification = NotificationCompat.Builder(this, com.videodl.app.VideoDlApp.CHANNEL_RESULT)
            .setSmallIcon(
                if (success) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .setContentTitle(if (success) "下载完成" else "下载失败")
            .setContentText("${title.take(40)}：${text.take(120)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(this).notify(RESULT_TAG, taskId.hashCode(), notification)
        }
    }

    private fun formatEta(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return if (s >= 3600) {
            String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        } else {
            String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        }
    }

    private fun unitFactor(unit: String): Double = when (unit.uppercase(Locale.ROOT).firstOrNull()) {
        'G' -> 1024.0 * 1024 * 1024
        'M' -> 1024.0 * 1024
        'K' -> 1024.0
        else -> 1.0
    }

    companion object {
        private const val ACTION_CANCEL_TASK = "com.videodl.app.action.CANCEL_TASK"
        private const val ACTION_CANCEL_ALL = "com.videodl.app.action.CANCEL_ALL"
        private const val EXTRA_TASK_ID = "task_id"

        private const val NOTIFICATION_ID = 1001
        private const val PROGRESS_TAG = "task-progress"
        private const val RESULT_TAG = "task-result"
        private const val PROGRESS_INTERVAL_MS = 400L

        private val SPEED_REGEX = Regex("""at\s+([\d.]+\s*[KMGT]i?B/s)""")
        private val TOTAL_SIZE_REGEX = Regex("""of\s+~?\s*([\d.]+)\s*([KMGT]i?B)""")
        private val INTERMEDIATE_NAME = Regex("""\.f\d+\.[A-Za-z0-9]+$""")

        private val MEDIA_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "mov", "flv", "avi", "ts", "3gp", "m4a", "mp3", "opus",
        )

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DownloadService::class.java))
        }

        fun cancelTask(context: Context, taskId: Long) {
            context.startService(
                Intent(context, DownloadService::class.java)
                    .setAction(ACTION_CANCEL_TASK)
                    .putExtra(EXTRA_TASK_ID, taskId)
            )
        }

        fun cancelAll(context: Context) {
            context.startService(
                Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL_ALL)
            )
        }
    }
}
