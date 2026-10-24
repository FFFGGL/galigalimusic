package com.galigalimusic.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 歌单曲目计数行 */
data class PlaylistCountRow(val playlistId: String, val cnt: Int)

@Dao
interface PlaylistSongDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(songs: List<PlaylistSongEntity>)

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position ASC")
    fun observeSongs(playlistId: String): Flow<List<PlaylistSongEntity>>

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getSongs(playlistId: String): List<PlaylistSongEntity>

    @Query(
        "SELECT playlistId, COUNT(*) AS cnt FROM playlist_songs GROUP BY playlistId"
    )
    fun observeCounts(): Flow<List<PlaylistCountRow>>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: String): Int

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND bvid = :bvid AND cid = :cid")
    suspend fun remove(playlistId: String, bvid: String, cid: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun clear(playlistId: String)

    /** 迁移老数据用：删掉 cid=0 的行（这些行补 cid 后以新主键重新插入） */
    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND cid = 0")
    suspend fun deleteZeroCidRows(playlistId: String)
}
