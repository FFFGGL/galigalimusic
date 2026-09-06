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
)
