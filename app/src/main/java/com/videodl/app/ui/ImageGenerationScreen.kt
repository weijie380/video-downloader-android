package com.videodl.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.videodl.app.imagegen.GeneratedImage
import java.io.File

@Composable
internal fun ImageGenerationScreen(viewModel: ImageGenerationViewModel, bottomPadding: Dp) {
    var settings by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<GeneratedImage?>(null) }
    var deleteIds by remember { mutableStateOf<Set<String>?>(null) }
    var paneHeight by remember { mutableIntStateOf(0) }
    val keyboard = LocalSoftwareKeyboardController.current
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val turns = viewModel.currentImages
    val chats = viewModel.images.groupBy { it.conversationId }.entries.sortedByDescending { (_, images) -> images.maxOf { it.created } }
    val selected = viewModel.models.firstOrNull { it.id == viewModel.selectedModel }
    val source = viewModel.reference
    LaunchedEffect(viewModel) { viewModel.refreshModels() }
    LaunchedEffect(viewModel.conversationId, turns.size, viewModel.busy, viewModel.message, paneHeight) {
        val count = turns.size + (if (turns.isEmpty() && !viewModel.busy) 1 else 0) +
            (if (viewModel.pendingPrompt != null) 1 else 0) + (if (viewModel.message != null) 1 else 0)
        val last = (count - 1).coerceAtLeast(0)
        list.requestScrollToItem(last, scrollOffset = paneHeight)
    }
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = bottomPadding)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = viewModel::newConversation, enabled = viewModel.canChangeHistory) { Text("新对话") }
            TextButton(onClick = { history = true }, enabled = viewModel.canChangeHistory) { Text("历史 · ${chats.size}") }
            TextButton(onClick = { settings = true }, enabled = !viewModel.busy && viewModel.ready) {
                Text(if (viewModel.hasKey) "密钥设置" else "设置密钥")
            }
        }
        Box {
            TextButton(onClick = { modelMenu = true }, enabled = !viewModel.busy && !viewModel.historyBusy) {
                Text("${selected?.name.orEmpty()}  ·  ${selected?.price ?: "价格以账户结算为准"}  ▾")
            }
            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                viewModel.models.forEach { model ->
                    DropdownMenuItem(text = { Text(model.name + if (model.available) "" else " · 暂不可用") },
                        enabled = model.available, onClick = { viewModel.selectModel(model.id); modelMenu = false })
                }
            }
        }
        viewModel.catalogMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().onSizeChanged { paneHeight = it.height },
            contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (turns.isEmpty() && !viewModel.busy) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("从一个想法开始。", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Text("第一轮描述画面，后续发送修改要求。\n每轮都会保留图片，可从任意一张继续。",
                        style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            items(turns, key = { it.id }) { image ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Surface(Modifier.fillMaxWidth(.9f), shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(image.prompt, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Text("第 ${turns.indexOf(image) + 1} 轮 · ${viewModel.models.firstOrNull { it.id == image.model }?.name ?: image.model}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AsyncImage(model = File(image.path), contentDescription = "第 ${turns.indexOf(image) + 1} 轮图片：${image.prompt.take(80)}",
                        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 300.dp)
                            .clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { preview = image })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { viewModel.continueFrom(image) }, enabled = viewModel.canChangeHistory) {
                            Text(if (viewModel.reference?.id == image.id) "当前参考图" else "从此图继续")
                        }
                        TextButton(onClick = { viewModel.saveImage(image) }, enabled = viewModel.saving == null && !viewModel.historyBusy && image.id !in viewModel.savedIds) {
                            Text(if (viewModel.saving == image.id) "保存中…" else if (image.id in viewModel.savedIds) "已保存" else "保存")
                        }
                        TextButton(onClick = { deleteIds = setOf(image.id) }, enabled = viewModel.canChangeHistory) { Text("删除") }
                    }
                }
            }
            viewModel.pendingPrompt?.let { pending -> item {
                Surface(Modifier.fillMaxWidth(.9f), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(pending, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (source == null) "正在生成…" else "正在修改参考图…", Modifier.padding(top = 8.dp))
            } }
            viewModel.message?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium) } }
        }
        source?.let { image ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(File(image.path), contentDescription = "下一轮参考图片", contentScale = ContentScale.Crop,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
                Text("修改第 ${turns.indexOf(image) + 1} 轮图片", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
        OutlinedTextField(value = viewModel.prompt, onValueChange = viewModel::changePrompt,
            enabled = !viewModel.busy && !viewModel.historyBusy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text(if (source == null) "描述你想生成的图片" else "继续说说怎么修改") }, minLines = 1, maxLines = 3)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (source == null) "联网生图，按平台价格计费" else "参考图和修改要求会发送到基元律动",
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (viewModel.busy) TextButton(onClick = viewModel::cancel) { Text("停止等待") }
            else Button(onClick = { keyboard?.hide(); viewModel.generate() },
                enabled = viewModel.ready && viewModel.hasKey && !viewModel.historyBusy && viewModel.saving == null && viewModel.prompt.isNotBlank()) { Text("发送") }
        }
    }
    if (settings) ImageKeyDialog(viewModel, onDismiss = { settings = false })
    if (history) AlertDialog(onDismissRequest = { history = false }, title = { Text("生图对话") },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (chats.isEmpty()) item { Text("暂无历史对话") }
                items(chats, key = { it.key }) { chat ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable {
                            viewModel.openConversation(chat.key); history = false
                        }.padding(vertical = 12.dp)) {
                            Text(chat.value.minBy { it.created }.prompt.take(32), maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text("${chat.value.size} 轮", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { deleteIds = chat.value.map { it.id }.toSet() }) { Text("删除") }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { history = false }) { Text("关闭") } })
    deleteIds?.let { ids ->
        AlertDialog(onDismissRequest = { deleteIds = null }, title = { Text("删除${if (ids.size == 1) "这条记录" else "整个对话"}？") },
            text = { Text("会删除所选记录和软件内的图片，已保存到下载目录的图片保留。") },
            confirmButton = { TextButton(onClick = { viewModel.deleteImages(ids); deleteIds = null; history = false }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteIds = null }) { Text("取消") } })
    }
    preview?.let { image ->
        Dialog(onDismissRequest = { preview = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                    AsyncImage(File(image.path), contentDescription = "生成图片预览", contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().weight(1f))
                    TextButton(onClick = { preview = null }, modifier = Modifier.fillMaxWidth()) { Text("关闭预览") }
                }
            }
        }
    }
}

@Composable
private fun ImageKeyDialog(viewModel: ImageGenerationViewModel, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("连接基元律动") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("在网站用户中心创建 API Key，然后粘贴到这里。密钥只在此手机加密保存。")
                OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("API Key") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = error != null, supportingText = { error?.let { Text(it) } }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://tokenrhythm.studio/account")))
                }) { Text("打开基元律动用户中心") }
                if (viewModel.hasKey) TextButton(onClick = { viewModel.clearKey(onDismiss) }) { Text("清除已保存密钥") }
            }
        },
        confirmButton = { TextButton(onClick = { viewModel.saveKey(key, onDismiss) { error = it } }, enabled = key.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
