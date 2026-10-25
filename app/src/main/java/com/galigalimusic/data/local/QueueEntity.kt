package com.galigalimusic.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 播放队列（「稍后播放 / 下一首播放」的待播列表）。
 *
 * 队列是 FIFO：skipNext / 自然播完时先弹队首播放，队列空才走原有分P/歌单逻辑。
 * currentQueueId 标记当前正在播放的队列项（仅高亮用）。
 */
@Entity(tableName = "play_queue")
data class QueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val position: Int,
    val bvid: String,
    val cid: Long,
    val title: String,
    val author: String,
    val coverUrl: String,
    val durationSec: Int,
)
