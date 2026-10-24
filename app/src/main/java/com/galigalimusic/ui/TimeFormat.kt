package com.galigalimusic.ui

/**
 * 时长格式化（全工程唯一实现）。
 *
 * 超过 1 小时用 H:MM:SS（有声书分P常达数小时），否则 MM:SS。
 * 搜索结果、播放页、歌词页、选集列表都走这里，避免各页面格式不一致。
 */
fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

fun formatDuration(sec: Int): String = formatMs(sec * 1000L)
