package com.videodl.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Brand = Color(0xFF1E6FD9)
private val BrandDark = Color(0xFF7FB2F0)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E5FB),
    onPrimaryContainer = Color(0xFF10305C),
    secondary = Color(0xFF4F5B6B),
    surface = Color(0xFFFBFBFD),
    surfaceVariant = Color(0xFFEDF0F5),
    background = Color(0xFFF6F7FA),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = BrandDark,
    onPrimary = Color(0xFF0A2B52),
    primaryContainer = Color(0xFF1B3F6E),
    onPrimaryContainer = Color(0xFFD7E5FB),
    secondary = Color(0xFFBFC8D6),
    surface = Color(0xFF14161A),
    surfaceVariant = Color(0xFF272B33),
    background = Color(0xFF101216),
    error = Color(0xFFF2B8B5),
)

@Composable
fun VideoDlTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

/** 把秒格式化为 "12:34" / "1:02:03"。 */
fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return ""
    val s = seconds
    return if (s >= 3600) {
        "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    } else {
        "%d:%02d".format(s / 60, s % 60)
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return ""
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> "%.2f GB".format(gb)
        mb >= 1 -> "%.1f MB".format(mb)
        else -> "%.0f KB".format(kb)
    }
}