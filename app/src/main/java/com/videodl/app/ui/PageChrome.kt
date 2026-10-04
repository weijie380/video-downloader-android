package com.videodl.app.ui

import android.os.Build
import android.graphics.RuntimeShader
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.videodl.app.data.DownloadTaskEntity
import com.videodl.app.data.SavedAsset
import com.videodl.app.data.TaskStatus

/** 回放导航栏后方的内容；独立图层模糊，不会影响图标文字或主页面。 */
@Composable
internal fun GlassBottomBar(
    currentPage: Int,
    backdrop: GraphicsLayer,
    contentOrigin: Offset,
    modifier: Modifier = Modifier,
    onPage: (Int) -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(32.dp)
    val frost = rememberGraphicsLayer()
    val lens = remember { if (Build.VERSION.SDK_INT >= 33) runCatching { LiquidGlassLens() }.getOrNull() else null }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val tint = if (dark) Color(0xFF182435) else Color.White
    val ink = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier.widthIn(max = 320.dp).fillMaxWidth()
            .shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = .12f),
                spotColor = Color.Black.copy(alpha = .15f))
            .onGloballyPositioned { origin = it.positionInRoot() }
            .clip(shape)
            .drawWithContent {
                val relative = origin - contentOrigin
                // 预留透镜采样边缘，折射时也能读取胶囊外侧的真实内容。
                val margin = 24.dp.toPx()
                val effect = if (Build.VERSION.SDK_INT >= 33 && lens != null)
                    lens.effect(size.width, size.height, margin, density, dark)
                else null
                frost.renderEffect = effect ?: BlurEffect(12.dp.toPx(), 12.dp.toPx(), TileMode.Clamp)
                frost.record(size = IntSize((size.width + margin * 2).toInt(), (size.height + margin * 2).toInt())) {
                    translate(margin - relative.x, margin - relative.y) { drawLayer(backdrop) }
                }
                translate(-margin, -margin) { drawLayer(frost) }
                drawRect(tint.copy(alpha = if (effect != null) {
                    if (dark) .58f else .48f
                } else { if (dark) .78f else .72f }))
                drawContent()
            }
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) .08f else .25f), Color.Transparent)))
            .border(1.dp, Brush.linearGradient(listOf(
                Color.White.copy(alpha = if (dark) .48f else .95f),
                Color.White.copy(alpha = .08f),
                Color.White.copy(alpha = if (dark) .26f else .65f))), shape)
            .selectableGroup().padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf("首页", "下载队列", "生图").forEachIndexed { page, label ->
            val selected = currentPage == page
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(26.dp))
                    .background(if (selected) primary.copy(alpha = if (dark) .22f else .12f) else Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onPage(page) })
                    .heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            ) {
                Icon(when (page) { 0 -> Icons.Default.Home; 1 -> Icons.AutoMirrored.Filled.List; else -> GenerateImageIcon },
                    contentDescription = null, tint = if (selected) primary else ink.copy(alpha = .7f),
                    modifier = Modifier.size(22.dp))
                Text(label, style = MaterialTheme.typography.labelMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = if (selected) primary else ink.copy(alpha = .8f))
            }
        }
    }
}

private val GenerateImageIcon = ImageVector.Builder("GenerateImage", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = androidx.compose.ui.graphics.SolidColor(Color.Black), pathFillType = androidx.compose.ui.graphics.PathFillType.EvenOdd) {
        moveTo(19f, 3f); lineTo(5f, 3f); curveTo(3.9f, 3f, 3f, 3.9f, 3f, 5f)
        lineTo(3f, 19f); curveTo(3f, 20.1f, 3.9f, 21f, 5f, 21f)
        lineTo(19f, 21f); curveTo(20.1f, 21f, 21f, 20.1f, 21f, 19f)
        lineTo(21f, 5f); curveTo(21f, 3.9f, 20.1f, 3f, 19f, 3f); close()
        moveTo(5f, 5f); lineTo(19f, 5f); lineTo(19f, 16f)
        lineTo(14f, 11f); lineTo(10f, 16f); lineTo(8f, 13f); lineTo(5f, 17f); close()
    }
    path(fill = androidx.compose.ui.graphics.SolidColor(Color.Black)) {
        moveTo(9f, 8f); curveTo(9f, 9.1f, 8.1f, 10f, 7f, 10f)
        curveTo(5.9f, 10f, 5f, 9.1f, 5f, 8f); curveTo(5f, 6.9f, 5.9f, 6f, 7f, 6f)
        curveTo(8.1f, 6f, 9f, 6.9f, 9f, 8f); close()
    }
}.build()

@Composable
internal fun TaskPreview(task: DownloadTaskEntity) {
    // 已下载图文直接读本机，网络断开后仍可看到封面。
    val localImage = if (task.isGallery && task.statusEnum == TaskStatus.COMPLETED)
        SavedAsset.fromJson(task.outputFilesJson).firstOrNull { it.mime.startsWith("image/") }?.uri else null
    val source = localImage ?: task.thumbnailUrl?.replaceFirst("http://", "https://")
    var loading by remember(source) { mutableStateOf(!source.isNullOrBlank()) }
    var failed by remember(source) { mutableStateOf(source.isNullOrBlank()) }
    val context = LocalContext.current
    val request = remember(source, task.platform) {
        ImageRequest.Builder(context).data(source)
            .addHeader("Referer", if (task.platform == "bilibili") "https://www.bilibili.com/" else task.url)
            .crossfade(android.animation.ValueAnimator.areAnimatorsEnabled()).build()
    }
    Box(Modifier.size(88.dp).clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (!source.isNullOrBlank()) AsyncImage(
            model = request, contentDescription = "${task.title ?: "作品"}的预览图",
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            onSuccess = { loading = false; failed = false },
            onError = { loading = false; failed = true },
        )
        if (loading || failed) Text(if (loading) "加载预览…" else "暂无预览",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Android 13+ 的实时透镜。只处理背后的页面，导航文字保持清晰。 */
@RequiresApi(33)
private class LiquidGlassLens {
    private val shader = RuntimeShader(SOURCE)
    private var cachedDensity = -1f
    private var cachedWidth = -1f
    private var cachedHeight = -1f
    private var cachedMargin = -1f
    private var cachedDark: Boolean? = null
    private var cachedEffect: androidx.compose.ui.graphics.RenderEffect? = null

    fun effect(width: Float, height: Float, margin: Float, density: Float, dark: Boolean): androidx.compose.ui.graphics.RenderEffect {
        if (cachedDensity != density || cachedWidth != width || cachedHeight != height ||
            cachedMargin != margin || cachedDark != dark) {
            shader.setFloatUniform("extent", width, height)
            shader.setFloatUniform("margin", margin)
            shader.setFloatUniform("pixel", density)
            shader.setFloatUniform("dark", if (dark) 1f else 0f)
            cachedDensity = density
            cachedWidth = width
            cachedHeight = height
            cachedMargin = margin
            cachedDark = dark
            // 尺寸/主题变化后重建效果，确保原生图层取得最新 uniform。
            val refraction = RenderEffect.createRuntimeShaderEffect(shader, "content")
            cachedEffect = RenderEffect.createChainEffect(refraction,
                RenderEffect.createBlurEffect(3f * density, 3f * density, Shader.TileMode.CLAMP))
                .asComposeRenderEffect()
        }
        return checkNotNull(cachedEffect)
    }

    companion object {
        // 胶囊截面的法线控制采样偏移；边缘弯曲更强，中心轻微放大。
        private const val SOURCE = """
            uniform shader content;
            uniform float2 extent;
            uniform float margin;
            uniform float pixel;
            uniform float dark;
            half4 main(float2 coord) {
                float2 p = coord - float2(margin);
                float radius = extent.y * 0.5;
                float2 axis = float2(clamp(p.x, radius, extent.x - radius), radius);
                float2 delta = p - axis;
                float distance = length(delta);
                float2 normal = delta / max(distance, 0.001);
                float edge = pow(clamp(distance / radius, 0.0, 1.0), 5.0);
                float2 center = extent * 0.5;
                float2 samplePoint = center + (p - center) * 0.96 - normal * edge * 10.0 * pixel;
                samplePoint += float2(margin);
                float2 split = normal * edge * 0.65 * pixel;
                half4 base = content.eval(samplePoint);
                half3 rgb = half3(content.eval(samplePoint + split).r, base.g,
                    content.eval(samplePoint - split).b);
                float rim = exp(-abs(distance - radius + pixel) / (1.3 * pixel));
                float light = clamp(dot(normal, normalize(float2(-0.5, -0.85))) * 0.5 + 0.5, 0.0, 1.0);
                rgb += half3(rim * light * (0.28 + dark * 0.1));
                rgb *= half(1.0 - rim * (1.0 - light) * 0.09);
                return half4(rgb, base.a);
            }
        """
    }
}
