package com.galigalimusic.ui.theme

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive

/**
 * 方案 B（Material You 动态取色）主题引擎。
 *
 * 基底是设计稿的深色中性面板；播放封面解析出主色后（ThemeEngine.accent），
 * 动态派生整套强调色（primary/containers），实现「整套界面颜色随封面变化」。
 *
 * C10：AMOLED 纯黑模式（背景/面板纯黑）支持手动常开与定时启用。
 */
object ThemeEngine {
    /** 当前播放封面提取出的主色；null = 未播放或取色关闭 */
    val accent = MutableStateFlow<Color?>(null)

    /** 动态取色开关（设置页可关，关闭后用手动主题色或回退 bilibili 粉基调） */
    val dynamicColor = MutableStateFlow(true)

    /** 手动主题色（设置页色板选择，选中即自动关闭动态取色）；null = 未选择 */
    val manualAccent = MutableStateFlow<Color?>(null)

    // ===== C10 AMOLED 纯黑 =====

    /** 手动常开纯黑（优先级最高） */
    val amoledManual = MutableStateFlow(false)

    /** 定时启用纯黑 */
    val amoledSchedOn = MutableStateFlow(false)

    /** 定时开始小时（含），0-23 */
    val amoledSchedStart = MutableStateFlow(22)

    /** 定时结束小时（不含），0-23 */
    val amoledSchedEnd = MutableStateFlow(6)

    /** 当前是否生效（每分钟由主题层重算；UI 直接读这个） */
    val amoledActive = MutableStateFlow(false)

    private const val PREFS = "theme_prefs"
    private const val KEY_DYNAMIC = "dynamic"
    private const val KEY_MANUAL_ACCENT = "manual_accent"
    private const val KEY_AMOLED = "amoled_manual"
    private const val KEY_SCHED_ON = "amoled_sched_on"
    private const val KEY_SCHED_START = "amoled_sched_start"
    private const val KEY_SCHED_END = "amoled_sched_end"

    @Volatile
    private var prefs: android.content.SharedPreferences? = null

    /** 启动时加载持久化主题设置（MainActivity.onCreate 调用） */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        dynamicColor.value = p.getBoolean(KEY_DYNAMIC, true)
        val argb = p.getInt(KEY_MANUAL_ACCENT, -1)
        manualAccent.value = if (argb == -1) null else Color(argb)
        amoledManual.value = p.getBoolean(KEY_AMOLED, false)
        amoledSchedOn.value = p.getBoolean(KEY_SCHED_ON, false)
        amoledSchedStart.value = p.getInt(KEY_SCHED_START, 22)
        amoledSchedEnd.value = p.getInt(KEY_SCHED_END, 6)
        recomputeAmoled(currentHour())
    }

    fun setDynamicColor(on: Boolean) {
        dynamicColor.value = on
        prefs?.edit()?.putBoolean(KEY_DYNAMIC, on)?.apply()
    }

    /** 选择预设主题色：固定色模式，同时关闭封面动态取色 */
    fun setManualAccent(color: Color) {
        manualAccent.value = color
        dynamicColor.value = false
        prefs?.edit()
            ?.putInt(KEY_MANUAL_ACCENT, color.toArgb())
            ?.putBoolean(KEY_DYNAMIC, false)
            ?.apply()
    }

    fun setAmoledManual(on: Boolean) {
        amoledManual.value = on
        prefs?.edit()?.putBoolean(KEY_AMOLED, on)?.apply()
        recomputeAmoled(currentHour())
    }

    fun setAmoledSchedule(on: Boolean, startHour: Int = amoledSchedStart.value, endHour: Int = amoledSchedEnd.value) {
        amoledSchedOn.value = on
        amoledSchedStart.value = startHour.coerceIn(0, 23)
        amoledSchedEnd.value = endHour.coerceIn(0, 23)
        prefs?.edit()
            ?.putBoolean(KEY_SCHED_ON, on)
            ?.putInt(KEY_SCHED_START, amoledSchedStart.value)
            ?.putInt(KEY_SCHED_END, amoledSchedEnd.value)
            ?.apply()
        recomputeAmoled(currentHour())
    }

    /** 当前小时是否落在定时区间（跨零点：22→6） */
    fun isInSchedule(hour: Int): Boolean {
        val s = amoledSchedStart.value
        val e = amoledSchedEnd.value
        return if (s == e) false
        else if (s < e) hour in s until e
        else hour >= s || hour < e
    }

    /** 重算生效状态（定时边界由主题层每分钟调用） */
    fun recomputeAmoled(hour: Int) {
        amoledActive.value =
            amoledManual.value || (amoledSchedOn.value && isInSchedule(hour))
    }

    private fun currentHour(): Int =
        java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
}

private fun blend(a: Color, b: Color, fraction: Float): Color = Color(
    red = a.red + (b.red - a.red) * fraction,
    green = a.green + (b.green - a.green) * fraction,
    blue = a.blue + (b.blue - a.blue) * fraction,
    alpha = 1f,
)

private fun lighten(c: Color, f: Float) = blend(c, Color.White, f)
private fun darken(c: Color, f: Float) = blend(c, Color.Black, f)

/** 预设主题色板（顺序即设置页展示顺序） */
data class ThemeColor(val name: String, val color: Color)

val ThemePalette = listOf(
    ThemeColor("B站粉", Color(0xFFFB7299)),
    ThemeColor("海蓝", Color(0xFF6AA5FF)),
    ThemeColor("湖青", Color(0xFF3FC1B0)),
    ThemeColor("苔绿", Color(0xFF7BC562)),
    ThemeColor("暖金", Color(0xFFE5B546)),
    ThemeColor("落日橙", Color(0xFFF08A4B)),
    ThemeColor("山茶红", Color(0xFFF0627A)),
    ThemeColor("薰衣草", Color(0xFFA98FE8)),
)

/** 由一个强调色派生方案 B 深色主题（近似 Material You tonal 角色）。 */
fun planBDarkScheme(accent: Color?): ColorScheme {
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

/**
 * C10：AMOLED 纯黑方案 —— 强调色不变，背景/面板全部压到纯黑。
 * OLED 纯黑像素不发光，深色界面下更省电、对比更强。
 */
fun planBAmoledScheme(accent: Color?): ColorScheme =
    planBDarkScheme(accent).copy(
        background = Color(0xFF000000),
        onBackground = Color(0xFFECE6F0),
        surface = Color(0xFF000000),
        onSurface = Color(0xFFECE6F0),
        surfaceVariant = Color(0xFF1C1922),
        onSurfaceVariant = Color(0xFFC6BFD2),
        surfaceContainerLowest = Color(0xFF000000),
        surfaceContainerLow = Color(0xFF07070A),
        surfaceContainer = Color(0xFF0C0B10),
        surfaceContainerHigh = Color(0xFF15131A),
        surfaceContainerHighest = Color(0xFF1E1B24),
        surfaceDim = Color(0xFF000000),
        surfaceBright = Color(0xFF26222E),
        outline = Color(0xFF7A7484),
        outlineVariant = Color(0xFF332F3C),
    )

@Composable
fun GaligaliMusicTheme(content: @Composable () -> Unit) {
    val accent by ThemeEngine.accent.collectAsState()
    val dynamic by ThemeEngine.dynamicColor.collectAsState()
    val manual by ThemeEngine.manualAccent.collectAsState()
    val amoled by ThemeEngine.amoledActive.collectAsState()

    // 定时切换：每分钟重算一次，跨小时边界准时生效
    LaunchedEffect(Unit) {
        while (isActive) {
            ThemeEngine.recomputeAmoled(
                java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            )
            delay(60_000)
        }
    }

    // 取色优先级：动态取色开 → 封面主色；否则 → 手动主题色；都没有 → 默认 bilibili 粉
    val seed = if (dynamic) accent else manual
    val scheme = if (amoled) planBAmoledScheme(seed) else planBDarkScheme(seed)
    MaterialTheme(colorScheme = scheme, content = content)
}
