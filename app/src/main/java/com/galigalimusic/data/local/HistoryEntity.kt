package com.galigalimusic.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 播放历史。主键 = bvid+cid 复合键：同一 BV 的不同分P 进度互相独立，不互相覆盖。
 */
@Entity(tableName = "play_history", primaryKeys = ["bvid", "cid"])
data class HistoryEntity(
    val bvid: String,
    val cid: Long,
    val title: String,
    val author: String,
    val coverUrl: String,
    val durationSec: Int,
    val lastPlayed: Long = System.currentTimeMillis(),
    /** 累计收听秒数（播放统计用；v4→v5 迁移补默认值 0） */
    val listenedSec: Long = 0L,
    /** 续播位置（毫秒）：退出/杀进程后从此处继续；播完自然归零（v5→v6 迁移补 0） */
    val resumePositionMs: Long = 0L,
)
