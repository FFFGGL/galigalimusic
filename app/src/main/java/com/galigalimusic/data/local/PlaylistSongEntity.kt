package com.galigalimusic.data.local

import androidx.room.Entity

/**
 * 歌单曲目。主键 = (playlistId, bvid, cid)：分P 可独立收藏；position 用于保序。
 */
@Entity(tableName = "playlist_songs", primaryKeys = ["playlistId", "bvid", "cid"])
data class PlaylistSongEntity(
    val playlistId: String,
    val bvid: String,
    val cid: Long,
    val title: String,
    val author: String,
    val coverUrl: String,
    val durationSec: Int,
    val position: Int,
    val addedAt: Long = System.currentTimeMillis(),
)
