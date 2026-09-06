package com.galigalimusic.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.Color
import coil.imageLoader
import coil.request.ImageRequest
import kotlin.math.min
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从封面图提取主色（不引入 Palette 依赖）。
 * 缩到 32px 后按 12 个色相桶做「饱和度×明度」加权统计，
 * 取权重最高的桶内平均色，再校准到适合深色主题的饱和度/明度区间。
 */
object CoverAccent {

    /** 主色提取失败/关闭时的默认值（bilibili 粉） */
    const val FALLBACK: Long = 0xFFFB7299

    /** 用 Coil 请求封面并提取主色；任何失败返回 null（调用方回退默认色）。 */
    suspend fun extract(context: Context, url: String?): Color? {
        if (url.isNullOrBlank()) return null
        return withContext(Dispatchers.Default) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false) // 需要读取像素，禁用硬件位图
                    .size(64)
                    .build()
                val drawable = context.imageLoader.execute(request).drawable
                val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return@runCatching null
                dominantColor(bitmap)
            }.getOrNull()
        }
    }

    /** 从已解码的 bitmap 计算主色。 */
    fun dominantColor(bitmap: Bitmap): Color {
        val w = 32
        val h = maxOf(2, (bitmap.height * 32f / bitmap.width).roundToInt())
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)

        val bucketWeight = FloatArray(12)
        val bucketR = FloatArray(12)
        val bucketG = FloatArray(12)
        val bucketB = FloatArray(12)
        val hsv = FloatArray(3)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = small.getPixel(x, y)
                AndroidColor.colorToHSV(c, hsv)
                val sat = hsv[1]
                val value = hsv[2]
                // 忽略近白/近黑/近灰像素（封面留白与文字）
                if (sat < 0.18f || value < 0.12f || value > 0.96f) continue
                val bucket = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
                val weight = sat * sat * min(value, 1f - value * 0.2f)
                bucketWeight[bucket] += weight
                bucketR[bucket] += AndroidColor.red(c) * weight
                bucketG[bucket] += AndroidColor.green(c) * weight
                bucketB[bucket] += AndroidColor.blue(c) * weight
            }
        }

        val best = bucketWeight.indices.maxByOrNull { bucketWeight[it] }
            ?: return Color(FALLBACK)
        if (bucketWeight[best] <= 0f) return Color(FALLBACK)

        val r = (bucketR[best] / bucketWeight[best]).roundToInt()
        val g = (bucketG[best] / bucketWeight[best]).roundToInt()
        val b = (bucketB[best] / bucketWeight[best]).roundToInt()
        AndroidColor.RGBToHSV(r, g, b, hsv)

        // 校准：深色主题里强调色要有足够饱和度、中高明度
        hsv[1] = min(1f, max(hsv[1], 0.42f))
        hsv[2] = min(0.86f, max(hsv[2], 0.55f))
        return Color(AndroidColor.HSVToColor(hsv))
    }
}
