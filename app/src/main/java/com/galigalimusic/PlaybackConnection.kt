package com.galigalimusic

import androidx.lifecycle.MutableLiveData
import com.galigalimusic.player.MusicService

/**
 * 进程内播放服务桥接：MainActivity 绑定服务后写入，UI 观察。
 */
object PlaybackConnection {
    val service = MutableLiveData<MusicService?>()
}
