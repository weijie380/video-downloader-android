package com.videodl.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.videodl.app.data.DownloadTaskEntity
import com.videodl.app.ytdlp.DouyinGallery
import com.videodl.app.ytdlp.DouyinGuestParser
import com.videodl.app.ytdlp.GallerySelection

@Composable
fun GalleryPickerDialog(task: DownloadTaskEntity, onSave: (Set<Int>) -> Unit, onDismiss: () -> Unit) {
    val gallery = remember(task.downloadInfoJson) {
        runCatching { DouyinGallery.fromJson(task.downloadInfoJson!!) }.getOrNull()
    }
    if (gallery == null) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text("图片信息已失效") },
            text = { Text("请关闭后重新解析这条作品。") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
        return
    }
    var chosen by rememberSaveable(task.id, task.downloadInfoJson) {
        mutableStateOf(ArrayList(GallerySelection.decode(task.gallerySelection)
            ?.takeIf { it.matches(gallery.images.size) }?.indices.orEmpty()))
    }
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 720.dp).fillMaxHeight(.88f),
            shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(16.dp)) {
                Text("选择图片", style = MaterialTheme.typography.titleLarge)
                Text("已选 ${chosen.size}/${gallery.images.size} 张 · 文案随选中图片保存",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    TextButton(onClick = { chosen = ArrayList(gallery.images.indices.toList()) }) { Text("全选") }
                    TextButton(onClick = { chosen = arrayListOf() }) { Text("清空") }
                }
                LazyVerticalGrid(columns = GridCells.Adaptive(112.dp), modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(gallery.images, key = { index, _ -> index }) { index, image ->
                        val selected = index in chosen
                        Column(Modifier.clip(RoundedCornerShape(14.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { check ->
                                chosen = ArrayList(if (check) chosen + index else chosen - index)
                            })) {
                            SubcomposeAsyncImage(
                                model = ImageRequest.Builder(context).data(image.urls.first())
                                    .addHeader("Referer", gallery.shareUrl)
                                    .addHeader("User-Agent", DouyinGuestParser.USER_AGENT).build(),
                                contentDescription = "第 ${index + 1} 张图片预览", contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(.8f),
                                loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(24.dp)) } },
                                error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("预览暂不可用", style = MaterialTheme.typography.labelSmall) } })
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("第 ${index + 1} 张", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                Checkbox(checked = selected, onCheckedChange = null)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(onClick = { onSave(chosen.toSet()) }) { Text("保存选择（${chosen.size}）") }
                }
            }
        }
    }
}
