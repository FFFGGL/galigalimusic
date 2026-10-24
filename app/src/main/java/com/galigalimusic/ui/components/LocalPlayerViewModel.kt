package com.galigalimusic.ui.components

import androidx.compose.runtime.compositionLocalOf
import com.galigalimusic.viewmodel.PlayerViewModel

/**
 * 全局播放器 ViewModel（在 MainActivity 的 AppRoot 里 provide）。
 *
 * 设置页等没有直接参数传入 playerVm 的页面，通过它拿到同一个实例，
 * 保证「音量增益 / 倍速 / 播放模式」在各页面看到的是同一份状态。
 */
val LocalPlayerViewModel = compositionLocalOf<PlayerViewModel> {
    error("LocalPlayerViewModel 未提供，应在 AppRoot 中 CompositionLocalProvider 注入")
}
