package com.videodl.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import com.videodl.app.ui.HomeScreen
import com.videodl.app.ui.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleSharedIntent(intent)
        requestNotificationPermissionIfNeeded()

        setContent {
            com.videodl.app.ui.VideoDlTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    HomeScreen(viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
    }

    /** 从其他 App「分享」过来的纯文本链接。 */
    private fun handleSharedIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_SEND) return
        if (intent.type?.startsWith("text/") != true) return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        viewModel.appendSharedText(shared)
        // 处理过的分享不再重复消费
        intent.removeExtra(Intent.EXTRA_TEXT)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}