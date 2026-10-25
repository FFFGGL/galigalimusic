package com.galigalimusic.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 播放/下载音质偏好（持久化在 playback prefs）。
 *
 * B 站 DASH 音频档位：30280=192K(高) / 30232=132K(中) / 30216=64K(低)。
 * 「仅 Wi-Fi 高音质」开启时，蜂窝网络下自动降到中档，省流量。
 */
object AudioQuality {
    const val HIGH = 30280
    const val MID = 30232
    const val LOW = 30216

    val OPTIONS = listOf(HIGH, MID, LOW)

    private fun prefs(context: Context) =
        context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    fun selected(context: Context): Int = prefs(context).getInt("audio_quality_qn", HIGH)

    fun setSelected(context: Context, qn: Int) {
        prefs(context).edit().putInt("audio_quality_qn", qn).apply()
    }

    fun wifiOnlyHigh(context: Context): Boolean =
        prefs(context).getBoolean("wifi_only_high", false)

    fun setWifiOnlyHigh(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("wifi_only_high", on).apply()
    }

    /** 当前网络下的生效档位 */
    fun effective(context: Context): Int {
        val qn = selected(context)
        if (wifiOnlyHigh(context) && qn > MID && !isWifi(context)) return MID
        return qn
    }

    fun label(qn: Int): String = when (qn) {
        LOW -> "低 · 64K 省流"
        MID -> "中 · 132K"
        else -> "高 · 192K"
    }

    private fun isWifi(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)
}
