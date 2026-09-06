package com.galigalimusic.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 歌单 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** 绑定的 B 站收藏夹 ID；为空表示普通本地歌单。 */
    val favMediaId: Long? = null,
    /** 最近一次从收藏夹同步的时间。 */
    val favSyncedAt: Long? = null,
)
