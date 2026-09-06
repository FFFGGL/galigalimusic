package com.galigalimusic.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlist: PlaylistEntity)

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE favMediaId = :mediaId LIMIT 1")
    suspend fun getByFavMediaId(mediaId: Long): PlaylistEntity?

    @Query("UPDATE playlists SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    @Query("UPDATE playlists SET favMediaId = :mediaId, favSyncedAt = :syncedAt WHERE id = :id")
    suspend fun markFavSynced(id: String, mediaId: Long, syncedAt: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: String)
}
