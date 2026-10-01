package com.videodl.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 国产 ROM（小米 MIUI / 华为 / OPPO 等）会在息屏或切到后台后积极清理进程，
 * 长视频下载很容易被系统掐断。这里提示用户把本应用加入电池优化白名单。
 *
 * 已经豁免时整条提示不显示，不打扰普通用户。
 */
@Composable
fun BackgroundHint() {
    val context = LocalContext.current
    var dismissed by remember { mutableStateOf(false) }
    if (dismissed) return

    val atRisk = remember { !isIgnoringBatteryOptimizations(context) }
    if (!atRisk) return

    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "小米 / MIUI 等系统会限制后台运行，允许「无限制」可避免长下载被系统中断。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    openBatteryOptimizationSettings(context)
                    dismissed = true
                },
            ) {
                Text("去设置")
            }
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return runCatching { manager.isIgnoringBatteryOptimizations(context.packageName) }
        .getOrDefault(true)
}

private fun openBatteryOptimizationSettings(context: Context) {
    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(request) }.onFailure {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}