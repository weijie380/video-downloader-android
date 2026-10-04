package com.videodl.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
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
    var preview by remember { mutableStateOf<GeneratedImage?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(viewModel) { viewModel.refreshModels() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("把想象，变成画面。", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("基元律动 · AI 生图", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (viewModel.hasKey) "API Key 已设置" else "先连接你的基元律动账号",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { settings = true }, enabled = !viewModel.busy && viewModel.ready) { Text("设置") }
                    }
                    Text("选择模型", style = MaterialTheme.typography.titleSmall)
                    Column(Modifier.selectableGroup()) {
                        viewModel.models.forEach { model ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .selectable(selected = model.id == viewModel.selectedModel,
                                    enabled = !viewModel.busy && model.available, role = Role.RadioButton,
                                    onClick = { viewModel.selectModel(model.id) })
                                .heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = model.id == viewModel.selectedModel, onClick = null,
                                    enabled = !viewModel.busy && model.available)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(model.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(if (!model.available) "暂不可用" else model.price?.let { "当前价格 $it" } ?: "价格以账户结算为准",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    viewModel.catalogMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    OutlinedTextField(value = viewModel.prompt, onValueChange = viewModel::changePrompt,
                        label = { Text("图片描述") }, placeholder = { Text("例如：海边日落，一座玻璃小屋，电影感光影") },
                        supportingText = { Text("${viewModel.prompt.length}/4000 · 每次生成 1 张") },
                        enabled = !viewModel.busy, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
                    Text("联网生成，按平台当前价格计费；描述会发送到基元律动。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (viewModel.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("正在生成，可切换到下载队列继续使用。", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth()) { Text("停止等待") }
                    } else {
                        Button(onClick = { keyboard?.hide(); viewModel.generate() },
                            enabled = viewModel.ready && viewModel.hasKey && viewModel.prompt.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("生成图片") }
                    }
                    viewModel.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        item { Text(if (viewModel.images.isEmpty()) "生成后，作品会留在这里。" else "我的作品 · ${viewModel.images.size}",
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        items(viewModel.images, key = { it.id }) { image ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                AsyncImage(model = File(image.path), contentDescription = "生成图片：${image.prompt.take(100)}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant).clickable { preview = image })
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(viewModel.models.firstOrNull { it.id == image.model }?.name ?: image.model,
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(image.prompt, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { viewModel.saveImage(image) },
                        enabled = viewModel.saving == null && image.id !in viewModel.savedIds,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(if (viewModel.saving == image.id) "保存中…" else if (image.id in viewModel.savedIds) "已保存" else "保存图片")
                    }
                }
            }
        }
    }
    if (settings) ImageKeyDialog(viewModel, onDismiss = { settings = false })
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
