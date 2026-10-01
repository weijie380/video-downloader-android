package com.videodl.app.ui

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.videodl.app.ytdlp.XCookieFile
import com.videodl.app.ytdlp.XSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 页面由用户打开和操作；没有自动解析帖子或启动下载。 */
@Suppress("SetJavaScriptEnabled")
@Composable
fun XSessionDialog(onChanged: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var openLogin by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(XSession.hasSavedSession(context)) }
    var message by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                            val buffer = ByteArray(262145)
                            var count = 0
                            while (count < buffer.size) {
                                val read = input.read(buffer, count, buffer.size - count)
                                if (read < 0) break
                                count += read
                            }
                            buffer.copyOf(count)
                        }
                            ?: error("无法读取 Cookie 文件")
                        require(bytes.size <= 262144) { "Cookie 文件过大，请仅导出 X 的 Cookie" }
                        XSession.save(context, bytes.toString(Charsets.UTF_8))
                    }
                }
                importing = false
                if (result.isSuccess) {
                    saved = true
                    message = "登录信息已保存。关闭后点失败任务的「重试」。"
                    onChanged()
                } else message = result.exceptionOrNull()?.message ?: "导入失败"
            }
        }
    }
    DisposableEffect(Unit) { onDispose { webView?.stopLoading(); webView?.destroy() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(12.dp)) {
                Text("X 登录", style = MaterialTheme.typography.titleLarge)
                Text(if (saved) "本机已保存登录信息（有效性需解析时确认）" else "尚未保存登录信息，当前按访客解析",
                    style = MaterialTheme.typography.bodySmall)
                Text("手机 X 客户端的登录不会自动共享到这里。请在 X 官方网页登录后保存，或导入 Netscape 格式 cookies.txt。登录信息仅保存在本机。",
                    style = MaterialTheme.typography.bodySmall)
                Text("请先开启 FlClash，并让视频下载器走 VPN。网页登录使用系统网络。",
                    style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { openLogin = true; message = null }, enabled = !importing) {
                        Text(if (openLogin) "登录网页已打开" else "打开官方登录页")
                    }
                    TextButton(onClick = { importer.launch(arrayOf("text/*", "application/octet-stream")) },
                        enabled = !importing) { Text(if (importing) "导入中…" else "导入 Cookie") }
                }
                if (saved) TextButton(onClick = {
                    webView?.stopLoading()
                    XSession.clear(context)
                    saved = false
                    openLogin = false
                    message = "已清除本机的 X 登录信息"
                    onChanged()
                }, enabled = !importing) { Text("清除登录信息") }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary) }
                if (openLogin) {
                    AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { viewContext ->
                        WebView(viewContext).apply {
                            webView = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = settings.userAgentString
                                .replace("; wv", "").replace(" Version/4.0", "")
                            settings.mediaPlaybackRequiresUserGesture = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            CookieManager.getInstance().setAcceptCookie(true)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val host = request.url.host.orEmpty().lowercase()
                                    val allowed = request.url.scheme == "https" &&
                                        (host == "x.com" || host.endsWith(".x.com") ||
                                            host == "twitter.com" || host.endsWith(".twitter.com"))
                                    if (!allowed) message = "请用 X 账号在官方网页登录；其他登录方式可使用 Cookie 文件导入。"
                                    return !allowed
                                }
                                override fun onPageFinished(view: WebView, url: String) {
                                    CookieManager.getInstance().flush()
                                }
                            }
                            loadUrl("https://x.com/i/flow/login")
                        }
                    }, onRelease = { view ->
                        view.stopLoading()
                        view.destroy()
                        webView = null
                    })
                } else Spacer(Modifier.weight(1f))
                Text("网页若无法登录，可从你已登录 X 的浏览器导出 Cookie 文件后导入。不要把 Cookie 发给他人。",
                    style = MaterialTheme.typography.labelSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onDismiss, enabled = !importing) { Text("关闭") }
                    if (openLogin) Button(onClick = {
                        val result = runCatching {
                            val manager = CookieManager.getInstance()
                            manager.flush()
                            val header = manager.getCookie("https://x.com/")
                                ?.takeIf { it.contains("auth_token=") }
                                ?: manager.getCookie("https://twitter.com/").orEmpty()
                            XSession.save(context, XCookieFile.fromHeader(header), webView?.settings?.userAgentString)
                        }
                        if (result.isSuccess) {
                            saved = true
                            message = "登录信息已保存。关闭后点失败任务的「重试」。"
                            onChanged()
                        } else message = result.exceptionOrNull()?.message ?: "保存失败"
                    }, enabled = !importing) { Text("保存登录信息") }
                }
            }
        }
    }
}
