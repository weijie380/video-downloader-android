package com.videodl.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.launch
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.CookieManager
import android.content.ClipData
import android.content.ClipboardManager
import org.json.JSONTokener
import com.videodl.app.ytdlp.DouyinGuestParser
import com.videodl.app.ytdlp.RequestPolicy
import com.videodl.app.ytdlp.XSession
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videodl.app.data.DownloadTaskEntity
import com.videodl.app.data.TaskStatus
import com.videodl.app.data.SavedAsset
import com.videodl.app.ytdlp.FormatBuilder
import com.videodl.app.ytdlp.FormatOption
import com.videodl.app.ytdlp.Platform

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: MainViewModel) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var galleryPickerTaskId by remember { mutableStateOf<Long?>(null) }
    var formatPickerTaskId by remember { mutableStateOf<Long?>(null) }
    var guestTask by remember { mutableStateOf<DownloadTaskEntity?>(null) }
    var errorDetails by remember { mutableStateOf<String?>(null) }
    var showNetwork by remember { mutableStateOf(false) }
    var showXSession by remember { mutableStateOf(false) }
    var savedGallery by remember { mutableStateOf<List<SavedAsset>?>(null) }
    var xSessionSaved by remember { mutableStateOf(XSession.hasSavedSession(context)) }
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val queueState = rememberLazyListState()
    val backdrop = rememberGraphicsLayer()
    val density = LocalDensity.current
    var barHeight by remember { mutableStateOf(80.dp) }
    var contentOrigin by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(viewModel) {
        viewModel.requestedPage.collect { page ->
            keyboard?.hide()
            // 队列页首次尚未布局时，scrollToItem 会等待布局，阻塞后面的跳页。
            if (page == 1) queueState.requestScrollToItem(0)
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) pager.animateScrollToPage(page)
            else pager.scrollToPage(page)
        }
    }
    BackHandler(pager.currentPage == 1) { scope.launch { pager.animateScrollToPage(0) } }
    val sessionBusy = viewModel.parsing || viewModel.updatingEngine || tasks.any {
        it.statusEnum.isRunning || it.statusEnum == TaskStatus.QUEUED
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                title = {
                    Column {
                        Text("视频下载器", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = viewModel.engineError
                                ?: viewModel.engineVersion?.let { "yt-dlp $it" }
                                ?: "解析引擎准备中…",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (viewModel.engineError != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { showNetwork = true }) { Text("网络") }
                    TextButton(
                        onClick = { viewModel.updateEngine() },
                        enabled = !viewModel.updatingEngine && !viewModel.parsing &&
                            tasks.none { it.statusEnum.isRunning } && viewModel.engineError == null,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (viewModel.updatingEngine) "更新中" else "更新引擎")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize()
                    .onGloballyPositioned { contentOrigin = it.positionInRoot() }
                    .drawWithContent {
                        backdrop.record { this@drawWithContent.drawContent() }
                        drawLayer(backdrop)
                    },
                verticalAlignment = Alignment.Top,
            ) { page ->
                LazyColumn(
                    state = if (page == 1) queueState else rememberLazyListState(),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = barHeight + 40.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (page == 0) {
                        item {
                            Column(Modifier.padding(top = 8.dp, bottom = 8.dp)) {
                                Text("把喜欢的内容，\n保存到手机。", style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(10.dp))
                                Text("抖音 · B站 · X   /   视频与图文", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item {
                            InputCard(text = viewModel.inputText,
                                parsing = viewModel.parsing || viewModel.updatingEngine,
                                onTextChange = viewModel::onInputChange,
                                onParse = { keyboard?.hide(); viewModel.addAndParse() })
                        }
                        item {
                            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (xSessionSaved) "X：登录信息已保存" else "X：需登录的视频请先登录",
                                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { showXSession = true }, enabled = !sessionBusy) { Text("X 登录") }
                                }
                            }
                        }
                        item { BackgroundHint() }
                    } else {
                        item {
                            QueueHeader(tasks, viewModel::startDownloads, viewModel::cancelAll, viewModel::clearFinished)
                        }
                        if (tasks.isEmpty()) item {
                            Column(Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("队列还是空的", style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.height(8.dp))
                                Text("在首页粘贴链接，解析后会自动出现在这里。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { scope.launch { pager.animateScrollToPage(0) } }) { Text("去首页添加") }
                            }
                        }
                        items(tasks.asReversed(), key = { it.id }) { task ->
                            TaskCard(task,
                                onDownload = {
                                    if (task.isGallery && !task.canDownload) galleryPickerTaskId = task.id
                                    else viewModel.startDownload(task.id)
                                },
                                onPickImages = { galleryPickerTaskId = task.id },
                                onPickFormat = { formatPickerTaskId = task.id },
                                onCancel = { viewModel.cancelTask(task.id) },
                                onRetry = { viewModel.retryTask(task.id) },
                                onRemove = { viewModel.removeTask(task.id) },
                                onOpen = {
                                    if (task.isGallery) savedGallery = SavedAsset.fromJson(task.outputFilesJson)
                                    else openDownloaded(context, task)
                                },
                                onGuest = { guestTask = task },
                                onError = { errorDetails = task.errorMessage })
                        }
                    }
                }
            }
            GlassBottomBar(
                currentPage = pager.currentPage,
                backdrop = backdrop,
                contentOrigin = contentOrigin,
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 16.dp)
                    .onGloballyPositioned { barHeight = with(density) { it.size.height.toDp() } },
                onPage = { page ->
                    keyboard?.hide()
                    scope.launch {
                        if (android.animation.ValueAnimator.areAnimatorsEnabled()) pager.animateScrollToPage(page)
                        else pager.scrollToPage(page)
                    }
                },
            )
        }
    }

    tasks.firstOrNull { it.id == galleryPickerTaskId }?.let { task ->
        GalleryPickerDialog(task, onSave = { indices ->
            viewModel.selectGalleryImages(task.id, indices)
            galleryPickerTaskId = null
        }, onDismiss = { galleryPickerTaskId = null })
    }

    val sheetTask = tasks.firstOrNull { it.id == formatPickerTaskId }
    if (sheetTask != null) {
        FormatPickerDialog(
            task = sheetTask,
            onPick = { option ->
                viewModel.selectFormat(sheetTask.id, option)
                formatPickerTaskId = null
            },
            onDismiss = { formatPickerTaskId = null },
        )
    }

    guestTask?.let { task ->
        GuestVerificationDialog(task,
            onResolved = { result -> guestTask = null; viewModel.acceptGuestResult(task.id, result) },
            onRetry = { guestTask = null; viewModel.retryTask(task.id) },
            onDismiss = { guestTask = null })
    }
    if (showXSession) XSessionDialog(
        onChanged = { xSessionSaved = XSession.hasSavedSession(context) },
        onDismiss = { showXSession = false; xSessionSaved = XSession.hasSavedSession(context) })
    savedGallery?.let { assets ->
        AlertDialog(onDismissRequest = { savedGallery = null }, title = { Text("图片及文案") },
            text = {
                LazyColumn {
                    items(assets, key = { it.uri }) { asset ->
                        val size = if (asset.bytes < 1024) "${asset.bytes} B" else formatBytes(asset.bytes)
                        TextButton(onClick = { openSavedAsset(context, asset) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${asset.name} · $size")
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { savedGallery = null }) { Text("关闭") } })
    }
    errorDetails?.let { error ->
        AlertDialog(onDismissRequest = { errorDetails = null }, title = { Text("错误详情") },
            text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(error) } },
            confirmButton = { TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("视频下载器错误", error))
                errorDetails = null
            }) { Text("复制详情") } },
            dismissButton = { TextButton(onClick = { errorDetails = null }) { Text("关闭") } })
    }
    if (showNetwork) NetworkDialog(onDismiss = { showNetwork = false })

    viewModel.message?.let { text ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            confirmButton = {
                TextButton(onClick = viewModel::dismissMessage) { Text("知道了") }
            },
            text = { Text(text) },
        )
    }
}

@Composable
private fun InputCard(
    text: String,
    parsing: Boolean,
    onTextChange: (String) -> Unit,
    onParse: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "粘贴链接",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth().height(120.dp),
                placeholder = { Text("每行一条，可一次粘贴多条抖音 / B站 / X 链接") },
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "支持多链接混合队列；B站默认第 1P，链接带 ?p=N 时按该分P。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onParse, enabled = !parsing) {
                    if (parsing) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(16.dp).height(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (parsing) "解析中" else "解析链接")
                }
            }
        }
    }
}

@Composable
private fun QueueHeader(
    tasks: List<DownloadTaskEntity>,
    onStart: () -> Unit,
    onCancelAll: () -> Unit,
    onClearFinished: () -> Unit,
) {
    val readyCount = tasks.count { it.statusEnum == TaskStatus.READY && it.canDownload }
    val runningCount = tasks.count { it.statusEnum.isRunning && it.statusEnum != TaskStatus.PARSING }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("下载队列", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("${tasks.size} 条任务 · 最多同时下载 2 条\n删除条目不会删除已保存文件",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onStart, enabled = readyCount > 0) {
                Text(if (readyCount > 0) "开始下载（$readyCount）" else "开始下载")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClearFinished) { Text("清理已结束") }
        }
        if (runningCount > 0) TextButton(onClick = onCancelAll) { Text("全部取消（$runningCount）") }
    }
}

@Composable
private fun TaskCard(
    task: DownloadTaskEntity,
    onDownload: () -> Unit,
    onPickFormat: () -> Unit,
    onPickImages: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
    onGuest: () -> Unit,
    onError: () -> Unit,
) {
    val status = task.statusEnum

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatformBadge(task)
                Spacer(Modifier.weight(1f))
                StatusChip(status)
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = if (status == TaskStatus.COMPLETED) "删除已完成条目" else "删除任务",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TaskPreview(task)
                Column(Modifier.weight(1f)) {
                    Text(task.title ?: task.url, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(task.uploader?.takeIf { it.isNotBlank() },
                        formatDuration(task.durationSeconds).takeIf { it.isNotBlank() }).joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(meta, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (task.isGallery) Text("图文作品", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }

            if (status == TaskStatus.READY || status == TaskStatus.QUEUED) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${if (task.isGallery) "内容" else "画质"}：${if (task.isGallery && !task.canDownload) "请选择图片" else task.formatLabel ?: "未选择"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (status == TaskStatus.DOWNLOADING || status == TaskStatus.MERGING ||
                status == TaskStatus.SAVING || status == TaskStatus.QUEUED
            ) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { task.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                val detail = buildString {
                    append("${(task.progress * 100).toInt()}%")
                    task.speedText?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                    if (task.etaSeconds > 0) append(" · 剩余 ${formatDuration(task.etaSeconds.toInt())}")
                    if (status == TaskStatus.MERGING) append(" · 正在合并音视频")
                    if (status == TaskStatus.SAVING) append(" · 正在写入下载目录")
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (status == TaskStatus.COMPLETED) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "已保存：${task.outputFileName ?: ""}" +
                        formatBytes(task.outputBytes).takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            task.outputNote?.takeIf { it.isNotBlank() }?.let { note ->
                Spacer(Modifier.height(2.dp))
                Text(
                    note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (status == TaskStatus.FAILED || (task.errorMessage != null && status == TaskStatus.READY)) {
                task.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (status == TaskStatus.FAILED) {
                Row {
                    TextButton(onClick = onError) { Text("查看/复制错误") }
                    if (Platform.fromKey(task.platform) == Platform.DOUYIN) {
                        TextButton(onClick = onGuest) { Text("游客验证") }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.height(2.dp))

            if (status == TaskStatus.READY) {
                Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                    Text(if (task.isGallery) {
                        if (task.canDownload) "下载已选图片及文案" else "选择要下载的图片"
                    } else "下载视频")
                }
                Spacer(Modifier.height(4.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (status) {
                    TaskStatus.READY -> {
                        if (task.isGallery) TextButton(onClick = onPickImages) { Text("选择图片") }
                        else TextButton(onClick = onPickFormat) { Text("选择画质") }
                        TextButton(onClick = onRetry) { Text("重新解析") }
                    }
                    TaskStatus.PENDING, TaskStatus.PARSING -> TextButton(onClick = onCancel) { Text("取消") }
                    TaskStatus.QUEUED, TaskStatus.DOWNLOADING,
                    TaskStatus.MERGING, TaskStatus.SAVING,
                    -> TextButton(onClick = onCancel) { Text("取消") }

                    TaskStatus.COMPLETED -> {
                        TextButton(onClick = onOpen) { Text(if (task.isGallery) "查看文件" else "打开") }
                        TextButton(onClick = onRetry) { Text("重新下载") }
                    }

                    TaskStatus.FAILED, TaskStatus.CANCELED -> TextButton(onClick = onRetry) { Text("重试") }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    task.url,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(2f, fill = false),
                )
            }
        }
    }
}

@Composable
private fun PlatformBadge(task: DownloadTaskEntity) {
    val platform = Platform.fromKey(task.platform)
    val (bg, fg) = when (platform) {
        Platform.DOUYIN -> Color(0xFF1B1B1F) to Color.White
        Platform.BILIBILI -> Color(0xFFFB7299) to Color.White
        Platform.X -> Color(0xFF2C2C2E) to Color.White
        Platform.OTHER -> Color(0xFF6B7280) to Color.White
    }
    val label = if (platform == Platform.BILIBILI && task.pageIndex != null) {
        "${platform.displayName} P${task.pageIndex}"
    } else {
        platform.displayName
    }

    Surface(color = bg, shape = RoundedCornerShape(6.dp)) {
        Text(
            label,
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun StatusChip(status: TaskStatus) {
    val (label, color) = when (status) {
        TaskStatus.PENDING -> "待解析" to MaterialTheme.colorScheme.onSurfaceVariant
        TaskStatus.PARSING -> "解析中" to MaterialTheme.colorScheme.primary
        TaskStatus.READY -> "待下载" to MaterialTheme.colorScheme.primary
        TaskStatus.QUEUED -> "排队中" to MaterialTheme.colorScheme.primary
        TaskStatus.DOWNLOADING -> "下载中" to MaterialTheme.colorScheme.primary
        TaskStatus.MERGING -> "合并中" to MaterialTheme.colorScheme.primary
        TaskStatus.SAVING -> "保存中" to MaterialTheme.colorScheme.primary
        TaskStatus.COMPLETED -> "已完成" to Color(0xFF1B8A3A)
        TaskStatus.FAILED -> "失败" to MaterialTheme.colorScheme.error
        TaskStatus.CANCELED -> "已取消" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(label, style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
private fun FormatPickerDialog(
    task: DownloadTaskEntity,
    onPick: (FormatOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = remember(task.formatOptionsJson) {
        FormatOption.listFromJson(task.formatOptionsJson)
    }
    val selectedId = remember(task.formatSelector) { task.formatSelector }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("选择画质", style = MaterialTheme.typography.titleMedium) },
        text = {
            if (options.isEmpty()) {
                Text("这条链接没有可用的画质信息，请点「重试」重新解析。")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(options, key = { it.id }) { option ->
                        val checked = option.selector == selectedId
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = checked, onClick = { onPick(option) })
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = checked, onClick = { onPick(option) })
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    option.qualityLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.weight(1f))
                                FormatBuilder.sizeLabel(option.approxBytes)?.let { size ->
                                    Text(
                                        "约 $size",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text(
                                option.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 40.dp),
                            )
                            option.containerNote?.let { note ->
                                Text(
                                    note,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(start = 40.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

private fun openSavedAsset(context: Context, asset: SavedAsset) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(asset.uri), asset.mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        android.widget.Toast.makeText(context, "没有可打开此文件的应用，请到系统下载目录查看", android.widget.Toast.LENGTH_LONG).show()
    }
}

private fun openDownloaded(context: Context, task: DownloadTaskEntity) {
    val uriString = task.outputUri ?: return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(uriString), "video/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

@Composable
private fun NetworkDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var proxy by remember { mutableStateOf(RequestPolicy.proxy(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("X 网络设置") }, text = {
        Column {
            Text("手机浏览器也应能访问 X。已有系统 VPN 时通常留空；仅在需要时填写代理。")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = proxy, onValueChange = { proxy = it; error = null },
                label = { Text("代理地址（可留空）") }, placeholder = { Text("socks5://主机:端口") },
                singleLine = true, isError = error != null)
            Text("手机上的 127.0.0.1 指手机本身；电脑代理需使用电脑的局域网地址。",
                style = MaterialTheme.typography.labelSmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            try { RequestPolicy.saveProxy(context, proxy); onDismiss() }
            catch (e: IllegalArgumentException) { error = e.message }
        }) { Text("保存") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

/** 只打开抖音官方分享页。Cookie 留在本机供游客解析使用。 */
@Suppress("SetJavaScriptEnabled")
@Composable
private fun GuestVerificationDialog(
    task: DownloadTaskEntity,
    onResolved: (com.videodl.app.ytdlp.ProbeResult) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val url = task.resolvedUrl ?: if (DouyinGuestParser.videoId(task.url) != null)
        DouyinGuestParser.shareUrl(task.url) else task.url
    fun readPage(view: WebView, retryIfEmpty: Boolean) {
        view.evaluateJavascript("JSON.stringify(window._ROUTER_DATA || {})") { encoded ->
            val raw = runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull()
            val result = runCatching { raw?.let { DouyinGuestParser.fromRouter(it, view.url ?: url) } }.getOrNull()
            if (result != null) onResolved(result)
            else if (retryIfEmpty) onRetry()
        }
    }
    DisposableEffect(Unit) { onDispose { webView?.stopLoading(); webView?.destroy() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(12.dp), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("抖音游客验证", style = MaterialTheme.typography.titleMedium)
                Text("无需注册或登录。在官方页面完成验证后，点下方重试。若内容仅登录后可见，游客方式无法下载。",
                    style = MaterialTheme.typography.bodySmall)
                AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
                    WebView(context).apply {
                        webView = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = DouyinGuestParser.USER_AGENT
                        settings.mediaPlaybackRequiresUserGesture = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        CookieManager.getInstance().setAcceptCookie(true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                CookieManager.getInstance().flush()
                                if (Uri.parse(url).path.orEmpty().contains("/slides/") &&
                                    DouyinGuestParser.videoId(url) != null) {
                                    view.loadUrl(DouyinGuestParser.shareUrl(url))
                                    return
                                }
                                readPage(view, false)
                            }
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                request.url.scheme != "https" ||
                                    com.videodl.app.ytdlp.UrlUtils.platformOf(request.url.toString()) != Platform.DOUYIN
                        }
                        loadUrl(url)
                    }
                })
                Row {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        CookieManager.getInstance().flush()
                        webView?.let { readPage(it, true) } ?: onRetry()
                    }) { Text("完成验证并重试") }
                }
            }
        }
    }
}
