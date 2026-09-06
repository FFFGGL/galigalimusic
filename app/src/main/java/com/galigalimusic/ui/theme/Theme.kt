package com.galigalimusic.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 方案 B（Material You 动态取色）主题引擎。
 *
 * 基底是设计稿的深色中性面板；播放封面解析出主色后（ThemeEngine.accent），
 * 动态派生整套强调色（primary/containers），实现「整套界面颜色随封面变化」。
 */
object ThemeEngine {
    /** 当前播放封面提取出的主色；null = 未播放或取色关闭 */
    val accent = MutableStateFlow<Color?>(null)

    /** 动态取色开关（设置页可关，关闭后回退 bilibili 粉基调） */
    val dynamicColor = MutableStateFlow(true)
}

private fun blend(a: Color, b: Color, fraction: Float): Color = Color(
    red = a.red + (b.red - a.red) * fraction,
    green = a.green + (b.green - a.green) * fraction,
    blue = a.blue + (b.blue - a.blue) * fraction,
    alpha = 1f,
)

private fun lighten(c: Color, f: Float) = blend(c, Color.White, f)
private fun darken(c: Color, f: Float) = blend(c, Color.Black, f)

/** 由一个强调色派生方案 B 深色主题（近似 Material You tonal 角色）。 */
fun planBDarkScheme(accent: Color?): androidx.compose.material3.ColorScheme {
    // 未取色（或取色关闭）时使用 bilibili 粉的 tonal 组合，与设计稿一致
    val seed = accent ?: Color(0xFFFB7299)

    val primary = lighten(seed, 0.42f)                    // 亮强调（按钮/高亮）
    val onPrimary = darken(seed, 0.72f)
    val primaryContainer = darken(seed, 0.45f)            // 大面积容器（hero/封面底）
    val onPrimaryContainer = lighten(seed, 0.72f)
    val secondary = lighten(blend(seed, Color(0xFF8A7A93), 0.45f), 0.28f)
    val secondaryContainer = blend(darken(seed, 0.52f), Color(0xFF332D3B), 0.35f)
    val onSecondaryContainer = lighten(seed, 0.66f)
    val tertiary = lighten(blend(seed, Color(0xFF7A4A63), 0.5f), 0.30f)

    return darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = darken(secondary, 0.7f),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = darken(tertiary, 0.7f),
        tertiaryContainer = darken(tertiary, 0.5f),
        onTertiaryContainer = lighten(tertiary, 0.7f),
        background = Color(0xFF141218),
        onBackground = Color(0xFFECE6F0),
        surface = Color(0xFF1E1B22),
        onSurface = Color(0xFFECE6F0),
        surfaceVariant = Color(0xFF332D3B),
        onSurfaceVariant = Color(0xFFCBC3D4),
        surfaceContainer = Color(0xFF211E26),
        surfaceContainerHigh = Color(0xFF2B2830),
        outline = Color(0xFF948F9D),
        outlineVariant = Color(0xFF4A444F),
        error = Color(0xFFF2B8B5),
        onError = Color(0xFF601410),
    )
}

@Composable
fun GaligaliMusicTheme(content: @Composable () -> Unit) {
    val accent by ThemeEngine.accent.collectAsState()
    val dynamic by ThemeEngine.dynamicColor.collectAsState()
    val scheme = planBDarkScheme(if (dynamic) accent else null)
    MaterialTheme(colorScheme = scheme, content = content)
}
